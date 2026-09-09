'use strict';
// Freeze public legacy data only. This script never connects to a database.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const cp = require('node:child_process');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '..');
const sha256 = value => crypto.createHash('sha256').update(value).digest('hex');
const clone = value => JSON.parse(JSON.stringify(value));
const commonFields = ['publisherRole','issuer','title','summary','city','region','amountWan','amountLabel','industries','tags','cooperationModes','sections','tone'];
const metadataFields = ['id','category','kind','status','publishedAt','sortOrder','isDemo'];
// Source-proven reuse only: never infer aliases by replacing arbitrary prefixes.
const scientistAliases = Object.fromEntries([
  'talent-clinical','talent-financing','talent-operation',
  'patent-chemistry','patent-api','patent-biologic','patent-tcm','patent-device','patent-nutrition','patent-clinical','patent-software','patent-other',
  'service-cro','service-cdmo','service-lizhu','service-finance','service-legal','service-ip'
].map(id => ['scientist-'+id,'enterprise-'+id]));

function captureSource(sourceDirectory) {
  const source = path.resolve(sourceDirectory);
  const names = ['data/phase1.js','data/investor.js','data/enterprise.js','data/scientist.js','data/manager.js','data/catalog-query.js','pages/index/index.js'];
  const sources = names.map(file => ({file,sha256:sha256(fs.readFileSync(path.join(source,file)))}));
  const assets = [...new Set(require(path.join(source,'data/phase1.js')).institutions.flatMap(x=>x.images))].map(file=>{const bytes=fs.readFileSync(path.join(source,file));return {file,sha256:sha256(bytes),byteSize:bytes.length,mimeType:'image/png',width:bytes.readUInt32BE(16),height:bytes.readUInt32BE(20)};});
  const git = (...args) => cp.execFileSync('git',['-c','safe.directory='+source.replaceAll('\\','/'),'-C',source,...args],{encoding:'utf8'}).trim();
  const get = name => clone(require(path.join(source,'data',name+'.js')));
  const entry = path.join(source,'pages/index/index.js');
  let page;
  vm.runInNewContext(fs.readFileSync(entry,'utf8'),{Page:definition=>{page=definition.data;},require:createRequire(entry)},{filename:entry});
  if (!Array.isArray(page?.tradeProjects)) throw new Error('Legacy home data missing; do not export an API-integrated frontend as a legacy seed.');
  return {schemaVersion:1,sourceCommit:git('rev-parse','HEAD'),sourceCommittedAt:git('show','-s','--format=%cI','HEAD'),sources,assets,
    phase1:get('phase1'),investor:get('investor'),enterprise:get('enterprise'),scientist:get('scientist'),manager:get('manager'),
    home:{tradeProjects:clone(page.tradeProjects),poolFilters:clone(page.poolFilters)}};
}

function buildManifest(source) {
  const frozen = clone(source);
  const exportedAt = new Date(frozen.sourceCommittedAt).toISOString();
  const sourceNote = '迁自原小程序示例内容；企业、金额、阶段、权利及资质等为原示例设定，发布不代表真实性认证。';
  const catalogSchema = {pool:{categories:[{value:'all',label:'全部'},{value:'project',label:'项目'}],filtersByCategory:{all:frozen.home.poolFilters,project:frozen.home.poolFilters}}};
  for (const audience of ['investor','enterprise','scientist','manager']) {
    catalogSchema[audience] = {categories:frozen[audience][audience+'Categories'],filtersByCategory:frozen[audience][audience+'FiltersByCategory']};
  }
  const attributesByCategory = {};
  for (const schema of Object.values(catalogSchema)) {
    for (const [category,filters] of Object.entries(schema.filtersByCategory)) {
      attributesByCategory[category] ??= new Set();
      for (const filter of filters) {
        const field=filter.field||filter.key;
        if (!['sort','kind','industry','cooperation',...commonFields].includes(field)) attributesByCategory[category].add(field);
      }
    }
  }
  const normalized = (item,resourceType) => {
    const allowed = new Set([...commonFields,...metadataFields,...attributesByCategory[resourceType]||[]]);
    for (const key of Object.keys(item)) if (!allowed.has(key)) throw new Error('Unsupported field '+resourceType+'.'+key+' in '+item.id);
    const payload = Object.fromEntries(commonFields.filter(key=>Object.hasOwn(item,key)).map(key=>[key,item[key]]));
    const attributes = Object.fromEntries([...attributesByCategory[resourceType]||[]].filter(key=>Object.hasOwn(item,key)).map(key=>[key,item[key]]));
    return {id:item.id,resourceType,kind:item.kind,businessStatus:item.status,publicationStatus:item.status==='withdrawn'?'OFFLINE':'PUBLISHED',
      sortOrder:item.sortOrder??0,version:1,publishedAt:item.publishedAt||exportedAt,...payload,attributes,sourceKind:'legacy_sample',sourceNote};
  };
  const byId = new Map();
  const resourceViews = [];
  const aliases = {};
  const sourceGroups = {pool:frozen.phase1.projects,investor:frozen.investor.investorResources,enterprise:frozen.enterprise.enterpriseResources,scientist:frozen.scientist.scientistResources,manager:frozen.manager.managerResources};
  for (const [audience,items] of Object.entries(sourceGroups)) {
    for (const [index,item] of items.entries()) {
      const category=item.category||'project';
      const id=audience==='scientist'?(scientistAliases[item.id]||item.id):item.id;
      if (audience==='scientist' && id!==item.id) {
        const canonical=frozen.enterprise.enterpriseResources.find(x=>x.id===id);
        const comparable = x => JSON.stringify(Object.fromEntries(Object.entries(x).filter(([key])=>!['id','sortOrder'].includes(key)).sort(([a],[b])=>a.localeCompare(b))));
        if (!canonical || comparable(canonical)!==comparable(item)) throw new Error('Alias source changed: '+item.id+' -> '+id);
      }
      const record=normalized({...item,id},category);
      if (byId.has(id) && audience!=='investor' && !(audience==='scientist' && id!==item.id)) throw new Error('Unmapped duplicate resource '+id);
      if (!byId.has(id) || audience==='investor') byId.set(id,record);
      aliases[audience+':'+item.id]=id;
      resourceViews.push({resourceId:id,audience,category,sortOrder:item.sortOrder??index+1,legacyId:item.id});
    }
  }
  const featured = frozen.home.tradeProjects.map((item,index) => {
    const id='home-featured-'+item.id;
    const amount=item.price==='面议'?null:Number(item.price.replace(/万(?:元)?$/,''));
    if (amount!==null && !Number.isFinite(amount)) throw new Error('Unknown home amount '+item.price);
    const kind=item.type==='技术需求'?'demand':item.type==='科技成果'?'supply':null;
    if (!kind) throw new Error('Unknown home resource type '+item.type);
    byId.set(id,{id,resourceType:'project',kind,businessStatus:'open',publicationStatus:'PUBLISHED',sortOrder:index+1,version:1,publishedAt:exportedAt,
      publisherRole:'platform',issuer:'',title:item.title,summary:'',city:item.city,region:'',amountWan:amount,amountLabel:kind==='demand'?'需求预算':'合作意向',
      price:item.price,industries:item.tags.filter(tag=>frozen.phase1.industryOptions.some(x=>x.value===tag)),tags:item.tags,cooperationModes:[],sections:[],tone:item.tone,
      attributes:{},sourceKind:'legacy_sample',sourceNote:'迁自原首页主推示例卡片；原数据仅有标题、类型、报价、标签与城市，没有正文、发布方或真实性认证依据。'});
    return {id:'featured-'+item.id,resourceId:id,publicationStatus:'PUBLISHED',sortOrder:index+1,version:1,publishedAt:exportedAt};
  });
  const publish = (item,index) => ({...item,publicationStatus:'PUBLISHED',sortOrder:index+1,version:1,publishedAt:item.date||exportedAt,sourceKind:'legacy_sample',sourceNote});
  const promos=frozen.phase1.promos.map(({desc,image,...item},index)=>publish({...item,description:desc,imageUrl:image||'',buttonText:'查看详情',action:{type:'article',targetId:item.id}},index));
  const policies=frozen.phase1.policies.map((item,index)=>({...publish(item,index),homeRecommended:index<3,sourceNote:'原小程序原创界面演示资料；保留原正文与日期，不对应现行政府政策、补贴或申报承诺。'}));
  const institutions=frozen.phase1.institutions.map(({isDemo,...item},index)=>({...publish(item,index),sourceNote:'迁自原小程序机构示例资料；图片为原创示意，未核实真实机构、场地、资质或服务承诺。'}));
  const canonicalResources=[...byId.values()];
  const active=canonicalResources.filter(x=>x.publicationStatus==='PUBLISHED'&&x.businessStatus==='open');
  const expectedStats={demands:active.filter(x=>x.kind==='demand').length,
    achievements:active.filter(x=>x.kind==='supply'&&(['project','technology'].includes(x.resourceType)||x.attributes.achievementType==='技术成果')).length,
    experts:active.filter(x=>x.resourceType==='talent'&&x.attributes.talentMaturity==='资深专家').length,
    // No manager-person or university/research-institute profile source exists in the legacy data.
    managers:0,institutions:0,
    patents:active.filter(x=>(x.resourceType==='patent'&&x.attributes.patentType!=='软著')||['专利包','纯专利权利'].includes(x.attributes.achievementType)).length};
  const recordCounts={resources:canonicalResources.length,resourceViews:resourceViews.length,featured:featured.length,promos:promos.length,policies:policies.length,institutions:institutions.length};
  const manifest={schemaVersion:1,sourceCommit:frozen.sourceCommit,sourceCommittedAt:frozen.sourceCommittedAt,exportedAt,sources:frozen.sources,assets:frozen.assets||[],sourceSnapshotSha256:sha256(JSON.stringify(frozen)),
    canonicalResources,resourceViews,aliases,featured,promos,policies,institutions,catalogSchema,recordCounts,expectedStats};
  manifest.manifestSha256=sha256(JSON.stringify(manifest));
  manifest.importBatchId='phase2-'+manifest.manifestSha256.slice(0,24);
  return manifest;
}

const commonColumns = `  id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  payload JSON NOT NULL,
  publication_status VARCHAR(16) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 1,
  published_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  import_batch_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL`;
const engine='ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci';
function renderSchema() {
  return `-- Phase 2 content schema. Select the existing business database before running.
-- Incremental DDL only; DDL commits independently from seed transactions.
CREATE TABLE IF NOT EXISTS content_resource (
${commonColumns},
  resource_type VARCHAR(32) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  business_status VARCHAR(16) NOT NULL,
  INDEX idx_resource_public (publication_status,business_status,resource_type,kind),
  INDEX idx_resource_published (published_at),
  INDEX idx_resource_batch (import_batch_id)
) ${engine};

CREATE TABLE IF NOT EXISTS content_resource_view (
  resource_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  audience VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  category VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  legacy_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (audience,legacy_id),
  UNIQUE KEY uq_view_resource (audience,resource_id),
  INDEX idx_view_category (audience,category,sort_order),
  CONSTRAINT fk_view_resource FOREIGN KEY (resource_id) REFERENCES content_resource(id)
) ${engine};

CREATE TABLE IF NOT EXISTS home_featured (
${commonColumns},
  resource_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  UNIQUE KEY uq_featured_resource (resource_id),
  INDEX idx_featured_public (publication_status,sort_order),
  CONSTRAINT fk_featured_resource FOREIGN KEY (resource_id) REFERENCES content_resource(id)
) ${engine};

${['home_promo','policy_article','service_institution'].map(table=>`CREATE TABLE IF NOT EXISTS ${table} (\n${commonColumns},\n  INDEX idx_${table}_public (publication_status,sort_order),\n  INDEX idx_${table}_batch (import_batch_id)\n) ${engine};`).join('\n\n')}

CREATE TABLE IF NOT EXISTS content_import_batch (
  id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_commit VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  record_counts JSON NOT NULL,
  status VARCHAR(16) NOT NULL,
  started_at DATETIME(6) NOT NULL,
  finished_at DATETIME(6) NULL,
  UNIQUE KEY uq_import_manifest (manifest_sha256)
) ${engine};

CREATE TABLE IF NOT EXISTS content_audit (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor VARCHAR(120) NOT NULL,
  operation VARCHAR(32) NOT NULL,
  entity_type VARCHAR(32) NOT NULL,
  entity_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  before_version BIGINT NULL,
  after_version BIGINT NULL,
  changed_fields JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  INDEX idx_audit_entity (entity_type,entity_id,created_at)
) ${engine};

CREATE TABLE IF NOT EXISTS content_media (
  id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  object_key VARCHAR(512) NOT NULL,
  mime_type VARCHAR(80) NOT NULL,
  byte_size BIGINT NOT NULL,
  width INT NOT NULL,
  height INT NOT NULL,
  public_url VARCHAR(2048) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  created_by VARCHAR(120) NOT NULL,
  UNIQUE KEY uq_media_key (object_key)
) ${engine};
`;
}

function sqlString(value) {
  if (value===null || value===undefined) return 'NULL';
  if (typeof value==='number') {
    if (!Number.isFinite(value)) throw new Error('Non-finite SQL number');
    return String(value);
  }
  const text=typeof value==='object'?JSON.stringify(value):String(value);
  return text.length?`CONVERT(0x${Buffer.from(text,'utf8').toString('hex')} USING utf8mb4)`:"''";
}
const datetime = value => new Date(value).toISOString().replace('T',' ').replace('Z','');
function seedTables(manifest) {
  const mapped = {id:'id',resourceType:'resource_type',kind:'kind',businessStatus:'business_status',publicationStatus:'publication_status',sortOrder:'sort_order',version:'version',publishedAt:'published_at',resourceId:'resource_id'};
  const content = (table,items) => ({table,rows:items.map(item => {
    const row={};
    const payload={};
    for (const [key,value] of Object.entries(item)) {
      if (mapped[key]) row[mapped[key]]=key==='publishedAt'?datetime(value):value;
      else payload[key]=value;
    }
    return {...row,payload,updated_at:datetime(manifest.exportedAt),import_batch_id:manifest.importBatchId};
  })});
  return [content('content_resource',manifest.canonicalResources),
    {table:'content_resource_view',rows:manifest.resourceViews.map(x=>({resource_id:x.resourceId,audience:x.audience,category:x.category,sort_order:x.sortOrder,legacy_id:x.legacyId}))},
    content('service_institution',manifest.institutions),content('policy_article',manifest.policies),content('home_promo',manifest.promos),content('home_featured',manifest.featured)];
}
function renderSeed(manifest) {
  const tables=seedTables(manifest);
  const temp=table=>'phase2_stage_'+table;
  const keyMatch=(table,a='t',b='s')=>table==='content_resource_view'?`${a}.audience=${b}.audience AND (${a}.legacy_id=${b}.legacy_id OR ${a}.resource_id=${b}.resource_id)`:table==='home_featured'?`(${a}.id=${b}.id OR ${a}.resource_id=${b}.resource_id)`:`${a}.id=${b}.id`;
  const matching=(table,rows)=>`(SELECT COUNT(*) FROM ${table} t JOIN ${temp(table)} s ON ${keyMatch(table)} WHERE ${Object.keys(rows[0]).map(c=>`t.${c} <=> s.${c}`).join(' AND ')}) = ${rows.length}`;
  const overlap=tables.map(({table})=>`SELECT '${table}' AS entity_type, ${table==='content_resource_view'?"CONCAT(s.audience, ':', s.legacy_id)":'s.id'} AS conflicting_id FROM ${temp(table)} s JOIN ${table} t ON ${keyMatch(table)}`).join('\nUNION ALL\n');
  const output=[`-- Generated from ${manifest.sourceCommit}; do not edit payload hex literals.
-- Dedicated Navicat query connection, existing business database, full-file execution.
-- Default is DRY RUN. After review change ONLY the next value to 0 and rerun.
-- Stop on SQL errors; execute ROLLBACK and reconnect before retrying.
SET @phase2_dry_run = 1;
SET @phase2_batch = ${sqlString(manifest.importBatchId)};
SET @phase2_hash = ${sqlString(manifest.manifestSha256)};
SET @phase2_lock = GET_LOCK(CONCAT(DATABASE(), ':phase2-content-import'), 30);
`];
  for (const {table,rows} of tables) {
    const columns=Object.keys(rows[0]);
    output.push(`CREATE TEMPORARY TABLE IF NOT EXISTS ${temp(table)} LIKE ${table};\nDELETE FROM ${temp(table)};\nINSERT INTO ${temp(table)} (${columns.join(',')}) VALUES\n${rows.map(row=>'('+columns.map(c=>sqlString(row[c])).join(',')+')').join(',\n')};\n`);
  }
  const stageChecks=tables.map(({table,rows})=>`(SELECT COUNT(*) FROM ${temp(table)}) = ${rows.length}`).join('\n  AND ');
  output.push(`SET @phase2_staging_ok = (${stageChecks});
START TRANSACTION;
SET @phase2_already = EXISTS(SELECT 1 FROM content_import_batch WHERE manifest_sha256=@phase2_hash AND status='COMPLETED');
SET @phase2_conflicts = (SELECT COUNT(*) FROM (\n${overlap}\n) conflicts);
SET @phase2_batch_conflict = EXISTS(SELECT 1 FROM content_import_batch WHERE id=@phase2_batch OR manifest_sha256=@phase2_hash);
SET @phase2_apply = (COALESCE(@phase2_lock,0)=1 AND @phase2_dry_run=0 AND @phase2_staging_ok=1 AND @phase2_already=0 AND @phase2_conflicts=0 AND @phase2_batch_conflict=0);
SELECT DATABASE() AS target_database, @phase2_dry_run AS dry_run, @phase2_lock AS lock_acquired, @phase2_staging_ok AS staging_complete,
  @phase2_already AS already_imported, IF(@phase2_already=1,0,@phase2_conflicts) AS conflicts, @phase2_apply AS apply_batch;
SELECT * FROM (\n${overlap}\n) conflicts WHERE @phase2_already=0;
INSERT INTO content_import_batch (id,manifest_sha256,source_commit,record_counts,status,started_at)
SELECT @phase2_batch,@phase2_hash,${sqlString(manifest.sourceCommit)},${sqlString(manifest.recordCounts)},'STARTED',UTC_TIMESTAMP(6)
WHERE @phase2_apply=1;
`);
  for (const {table,rows} of tables) {
    const columns=Object.keys(rows[0]);
    output.push(`INSERT INTO ${table} (${columns.join(',')})\nSELECT ${columns.map(c=>'s.'+c).join(',')} FROM ${temp(table)} s\nWHERE @phase2_apply=1 AND EXISTS(SELECT 1 FROM content_import_batch WHERE id=@phase2_batch AND status='STARTED');\n`);
  }
  output.push(`INSERT INTO content_audit (actor,operation,entity_type,entity_id,before_version,after_version,changed_fields,created_at)
SELECT 'phase2-importer','IMPORT','import_batch',@phase2_batch,NULL,1,${sqlString(Object.keys(manifest.recordCounts))},UTC_TIMESTAMP(6)
WHERE @phase2_apply=1 AND EXISTS(SELECT 1 FROM content_import_batch WHERE id=@phase2_batch AND status='STARTED');
SET @phase2_complete = (\n  ${tables.map(({table,rows})=>matching(table,rows)).join('\n  AND ')}
  AND (SELECT COUNT(*) FROM content_audit WHERE actor='phase2-importer' AND operation='IMPORT' AND entity_type='import_batch' AND entity_id=@phase2_batch) = 1\n);
UPDATE content_import_batch SET status='COMPLETED',finished_at=UTC_TIMESTAMP(6)
WHERE id=@phase2_batch AND status='STARTED' AND @phase2_apply=1 AND @phase2_complete=1;
SET @phase2_commit = (@phase2_apply=1 AND @phase2_complete=1 AND EXISTS(SELECT 1 FROM content_import_batch WHERE id=@phase2_batch AND status='COMPLETED'));
SET @phase2_finish_sql = IF(@phase2_commit=1, 'COMMIT', 'ROLLBACK');
PREPARE phase2_finish FROM @phase2_finish_sql;
EXECUTE phase2_finish;
DEALLOCATE PREPARE phase2_finish;
SELECT CASE WHEN @phase2_already=1 THEN 'SKIPPED_ALREADY_IMPORTED'
  WHEN @phase2_dry_run=1 AND @phase2_conflicts=0 AND @phase2_batch_conflict=0 AND @phase2_staging_ok=1 AND @phase2_lock=1 THEN 'DRY_RUN_READY'
  WHEN @phase2_commit=1 THEN 'IMPORTED'
  ELSE 'NOT_IMPORTED_CHECK_CONFLICTS_AND_ERRORS' END AS result,
  @phase2_batch AS batch_id, @phase2_hash AS manifest_sha256;
SELECT RELEASE_LOCK(CONCAT(DATABASE(), ':phase2-content-import')) AS import_lock_released;
`);
  return output.join('\n');
}

function renderVerify(manifest) {
  return `-- Read-only verification. Select the same existing business database.
SELECT DATABASE() AS target_database, VERSION() AS mysql_version;
SELECT id,manifest_sha256,source_commit,record_counts,status,started_at,finished_at
FROM content_import_batch WHERE id=${sqlString(manifest.importBatchId)};
${seedTables(manifest).filter(x=>x.table!=='content_resource_view').map(({table,rows})=>`SELECT '${table}' AS entity_type, ${rows.length} AS expected_initial_rows, COUNT(*) AS actual_batch_rows FROM ${table} WHERE import_batch_id=${sqlString(manifest.importBatchId)};`).join('\n')}
SELECT audience,COUNT(*) AS view_rows FROM content_resource_view GROUP BY audience ORDER BY audience;
SELECT resource_type,COUNT(*) AS resources,SUM(publication_status='PUBLISHED' AND business_status='open') AS active_resources FROM content_resource GROUP BY resource_type ORDER BY resource_type;
SELECT
  COALESCE(SUM(kind='demand'),0) AS demands,
  COALESCE(SUM(kind='supply' AND (resource_type IN ('project','technology') OR JSON_UNQUOTE(JSON_EXTRACT(payload,'$.attributes.achievementType'))='技术成果')),0) AS achievements,
  COALESCE(SUM(resource_type='talent' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.attributes.talentMaturity'))='资深专家'),0) AS experts,
  0 AS managers,
  0 AS institutions,
  COALESCE(SUM((resource_type='patent' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.attributes.patentType'))<>'软著') OR JSON_UNQUOTE(JSON_EXTRACT(payload,'$.attributes.achievementType')) IN ('专利包','纯专利权利')),0) AS patents
FROM content_resource WHERE publication_status='PUBLISHED' AND business_status='open';
-- Initial expected stats: ${JSON.stringify(manifest.expectedStats)}. Later admin edits legitimately change these values.
SELECT v.audience,v.legacy_id,v.resource_id FROM content_resource_view v
WHERE v.legacy_id IN ('scientist-data-other','enterprise-data-other') ORDER BY v.audience;
SELECT id,business_status,publication_status FROM content_resource WHERE business_status<>'open';
SELECT id,JSON_LENGTH(JSON_EXTRACT(payload,'$.images')) AS image_references FROM service_institution ORDER BY id;
SELECT id FROM content_resource WHERE JSON_EXTRACT(payload,'$.sourceKind') IS NULL OR JSON_EXTRACT(payload,'$.sourceNote') IS NULL;
SELECT v.resource_id FROM content_resource_view v LEFT JOIN content_resource r ON r.id=v.resource_id WHERE r.id IS NULL;
`;
}
function writeArtifacts(source, outputRoot=root) {
  const manifest=buildManifest(source);
  const adminSqlPath=path.join(root,'src/main/resources/migration/003-admin.sql');
  const adminSql=fs.existsSync(adminSqlPath)?fs.readFileSync(adminSqlPath,'utf8'):'';
  const outputs={'migration/phase2-source.json':JSON.stringify(source,null,2)+'\n','migration/phase2-manifest.json':JSON.stringify(manifest,null,2)+'\n',
    'src/main/resources/catalog-schema.json':JSON.stringify(manifest.catalogSchema,null,2)+'\n','src/main/resources/migration/002-content.sql':renderSchema(),
    'migration/001_schema.sql':renderSchema()+(adminSql?'\n-- Standard admin session and throttle tables; apply this section once.\n'+adminSql:''),'migration/002_seed.sql':renderSeed(manifest),'migration/003_verify.sql':renderVerify(manifest)};
  for (const [file,contents] of Object.entries(outputs)) {const full=path.join(outputRoot,file);fs.mkdirSync(path.dirname(full),{recursive:true});fs.writeFileSync(full,contents);}
  return manifest;
}
if (require.main===module) {
  const sourceIndex=process.argv.indexOf('--source');
  const source=sourceIndex===-1?JSON.parse(fs.readFileSync(path.join(root,'migration/phase2-source.json'),'utf8')):captureSource(process.argv[sourceIndex+1]);
  const manifest=writeArtifacts(source);
  console.log(JSON.stringify({batchId:manifest.importBatchId,manifestSha256:manifest.manifestSha256,counts:manifest.recordCounts,expectedStats:manifest.expectedStats},null,2));
}
module.exports={captureSource,buildManifest,renderSchema,renderSeed,renderVerify,sqlString,writeArtifacts};
