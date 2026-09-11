'use strict';
const assert=require('node:assert/strict');
const schema=require('../../src/main/resources/catalog-schema.json');
const admin=require('../../src/main/resources/static/admin/admin.js');
// Independently transcribed from the user's final 2026-09-11 diagram.
const expected={
 scientist:[
  ["technology","技术",[["kind","交易意向",["supply","demand"]],["technologyField","技术领域",["小分子","抗体","多肽","类器官","基因与细胞治疗","其他领域"]],["researchStage","研发阶段",["早期研究","临床前研究","临床研究","申请上市","批准上市"]],["cooperationMode","合作模式",["技术转让","技术授权","其他模式"]]]],
  ["patent","专利",[["patentType","专利类型",["发明专利","实用新型专利","软著"]],["patentField","专利领域",["小分子","抗体","多肽","类器官","基因与细胞治疗","其他领域"]],["patentStatus","专利状态",["有效授权","实质审查中","即将过期"]],["cooperationMode","合作模式",["专利转让","专利授权","其他模式"]]]],
  ["data","数据",[["kind","交易意向",["supply","demand"]],["dataType","数据类型",["临床前全套数据","临床研究数据","真实世界RWD数据","其他数据"]],["complianceMaturity","合规成熟度",["仅限研究使用","已脱敏合规","可商用授权","其他"]],["cooperationMode","合作模式",["数据永久授权","单次项目调取","其他模式"]]]],
  ["finance","投融资",[],true],
  ["talent","人才",[["kind","交易意向",["supply","demand"]],["talentType","人才类型",["投融资产业专家","商业化运营人才","其他人才"]],["talentMaturity","人才成熟度",["资深专家","初级从业者"]],["cooperationMode","合作模式",["全职","项目制","技术顾问"]]]],
  ["service","服务机构",[["kind","交易意向",["supply","demand"]],["serviceType","服务类型",["CXO服务","骊珠整体解决方案","增值服务"]],["qualification","机构资质",["丰富落地案例"]],["cooperationMode","合作模式",["单项目合作","年度框架合作","其他模式"]]]]
 ],
 investor:[
  ["project","项目",[["kind","交易意向",["supply","demand"]],["projectType","项目类型",["早期创新药项目","仿制药项目","改良型新药项目","中药创新项目","原料药/API 项目","其他项目"]],["researchStage","研发阶段",["早期研究","临床前研究","临床研究","申请上市","批准上市"]],["indication","适应症赛道",["肿瘤疾病","代谢与内分泌疾病","神经系统疾病","心血管系统","其他适应症"]]]],
  ["mah","MAH",[["kind","交易意向",["supply","demand"]],["mahType","MAH类型",["化学药制剂 MAH 批件","生物药制剂 MAH 批件","中药 MAH 批件","其他批件"]],["approvalStatus","批件状态",["已获批","其他状态"]],["tradeMode","交易模式",["MAH 转让","股权并购","委托生产","其他合作模式"]]]],
  ["scene","场景",[["sort","排序方式",["default","newest"]],["sceneType","场景业态",["疗愈空间","社区康养场景","消费医疗场景","中医药特色服务场景","其他业态场景"]],["maturity","落地成熟度",["试点验证阶段","小范围运营","规模化可复制","其他阶段"]],["investmentMode","投资模式",["股权投资","品牌授权","加盟扩张","其他模式"]]]]
 ],
 manager:[
  ["cro","CRO服务",[["kind","交易意向",["supply","demand"]],["serviceCapability","服务能力",["委托研发","CMC研究","药理毒理研究","临床试验服务","BE试验","注册申报","其他能力"]],["technologyField","技术领域",["小分子化药","大分子生物药","其他领域"]],["providerStrength","服务商实力",["具备 GLP/GCP 资质","全国多中心"]]]],
  ["cdmo","CDMO服务",[["kind","交易意向",["supply","demand"]],["serviceCapability","服务能力",["大规模细胞培养","抗体药物规模化生产","蛋白药物规模化生产","其他能力"]],["technologyField","技术领域",["小分子化药","大分子生物药","其他领域"]],["providerStrength","服务商实力",["自有GMP 车间","无菌产线"]]]],
  ["solution","骊珠整体解决方案",[["sort","排序方式",["default","newest"]],["serviceCapability","服务能力",["项目可行性评价","资源协同","项目全周期托管","交易结构设计","项目估值与商业包装","其他服务"]],["technologyField","技术领域",["小分子化药","大分子生物药","其他领域"]],["institutionRegion","机构属地",["重庆","国内"]]]],
  ["ip_service","知识产权服务",[["kind","交易意向",["supply","demand"]],["serviceCapability","服务能力",["专利挖掘","专利布局","专利申请","其他能力"]],["serviceField","服务领域",["小分子化药","大分子生物药","其他领域"]],["providerStrength","服务商实力",["专利代理师","涉外专利"]]]],
  ["financing_service","投融资服务",[["sort","排序方式",["default","newest"]],["serviceType","服务类型",["早期融资","中期融资","后期融资"]],["serviceField","服务领域",["小分子化药","大分子生物药","其他领域"]],["providerStrength","服务商实力",["自有产业基金"]]]]
 ]
};
for(const [audience,categories] of Object.entries(expected)) {
 assert.deepEqual(schema[audience].categories.map(c=>[c.value,c.label,!!c.pending]),categories.map(([key,label,,pending])=>[key,label,!!pending]),audience+' category order');
 for(const [category,,fields,pending]of categories) {
  const filters=schema[audience].filtersByCategory[category];
  assert.deepEqual(filters.map(f=>[f.key,f.label,f.options.filter(o=>o.value!=='all').map(o=>o.value)]),fields,audience+'/'+category);
  assert.equal(new Set(filters.map(f=>f.key)).size, pending?0:4);
  assert.ok(!filters.some(f=>f.key==='kind')||filters.find(f=>f.key==='kind').options.slice(1).map(o=>o.label).join('/')==='产出/诉求');
 }
}
assert.deepEqual(admin.resourceCategories(schema,'manager').map(c=>c.value),['cro','cdmo','solution','ip_service','financing_service']);
assert.ok(admin.attributeDefinitions(schema,'achievement').some(f=>f.field==='achievementType'),'historical achievements stay editable');
assert.equal(admin.resourceFilters(schema,'investor','project').find(f=>f.field==='investorIndications').multiple,true);
assert.equal(admin.resourcePayload(schema,{resourceType:'project',attributes:{indications:['眼科'],researchStage:'1期临床'}},'project',{investorIndications:['肿瘤疾病']}).indications[0],'眼科');
console.log('PASS final scientist, investor and service-provider catalog contract');
