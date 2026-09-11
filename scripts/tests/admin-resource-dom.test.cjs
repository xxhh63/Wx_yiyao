'use strict';
// Read-only regression: executes the checkout's real helpers/renderViews with a minimal DOM.
// Covers serialized form state only; native validation, layout and actual HTTP save need browser tests.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const repo=process.argv[2]||path.resolve(__dirname,'../..');
const source=fs.readFileSync(path.join(repo,'src/main/resources/static/admin/admin.js'),'utf8');
const catalogs=JSON.parse(fs.readFileSync(path.join(repo,'src/main/resources/catalog-schema.json'),'utf8'));
function between(start,end){const a=source.indexOf(start),b=source.indexOf(end,a);assert.ok(a>=0&&b>a,'source anchors still exist');return source.slice(a,b);}
const helpers=source.slice(0,source.indexOf("if (typeof document !== 'undefined')"));
const domHelpers=between('  function el(','  function message(')+between('  function field(','  function value(');
const render=between('  function renderViews(','  function readEditor(');
class Node {
  constructor(tag){this.tag=tag;this.children=[];this.dataset={};this.handlers={};this._value='';this.hidden=false;this.className='';}
  append(...nodes){for(const node of nodes){this.children.push(node);node.parentElement=this;}}
  replaceChildren(...nodes){this.children=[];this.append(...nodes);}
  addEventListener(name,handler){(this.handlers[name]??=[]).push(handler);}
  fire(name){for(const handler of this.handlers[name]||[])handler({target:this});}
  get options(){return this.children;}
  get value(){return this.tag==='select'?(this.children.find(x=>x.selected)?.value??(this.multiple?'':this.children[0]?.value)??''):this._value;}
  set value(v){v=String(v);if(this.tag==='select'){for(const x of this.children)x.selected=x.value===v;}else this._value=v;}
  get selectedOptions(){return this.children.filter(x=>x.selected);}
  closest(selector){let node=this;while(node){if(selector[0]==='.'?node.className.split(' ').includes(selector.slice(1)):node.tag===selector)return node;node=node.parentElement;}return null;}
  querySelectorAll(selector){
    const all=this.children.flatMap(x=>[x,...x.querySelectorAll('*')]);
    if(selector==='*')return all;
    if(selector==='[data-resource-field]')return all.filter(x=>x.dataset.resourceField);
    const match=selector.match(/^\[name=([^\]]+)\](:checked)?$/);
    if(match)return all.filter(x=>x.name===match[1]&&(!match[2]||x.checked));
    throw new Error('Unsupported DOM selector: '+selector);
  }
  querySelector(selector){return this.querySelectorAll(selector)[0]||null;}
}
let host;
const state={catalogs,resourceAudience:'enterprise',editor:{}};
const audiences=[['pool','pool'],['investor','investor'],['enterprise','enterprise'],['scientist','scientist'],['manager','manager']];
const context=vm.createContext({URL,URLSearchParams,Map,Set,state,audiences,document:{createElement:tag=>new Node(tag)},$:()=>({elements:{namedItem:name=>control(name)}})});
vm.runInContext(helpers+'\nlet controlId=0;\n'+domHelpers+render+'\nglobalThis.render=renderViews;globalThis.field=field;',context);
function control(name){const node=host.querySelector('[name='+name+']');assert.ok(node,'control exists: '+name);return node;}
function reset(item){state.editor={};state.resourceAudience='enterprise';host=new Node('div');for(const name of ['industries','cooperationModes','kind'])context.field(host,name,name,item[name]||'');context.render(host,item);}
function change(name,value){const input=control(name);input.value=value;input.fire('change');}
function role(value){change('resourceAudience',value);}
function category(value){change('resourceType',value);}
function snapshot(){return JSON.parse(JSON.stringify(state.editor.readResourceClassification()));}
function views(){return snapshot().views.sort((a,b)=>a.audience.localeCompare(b.audience));}
function viewRoles(){return views().map(view=>view.audience);}
function extra(role){const checkbox=host.querySelectorAll('[name=extraAudience]').find(input=>input.value===role);assert.ok(checkbox,'extra role exists: '+role);return {checkbox,order:checkbox.closest('.extra-view-row').querySelector('[name=extraViewOrder]')};}
let failures=0;
function test(name,fn){try{fn();console.log('PASS '+name);}catch(error){failures++;console.error('FAIL '+name+'\n'+error.stack);}}
const existing={id:'existing',resourceType:'technology',attributes:{technologyField:'药物化学',technologyMaturity:'小试'},views:[{audience:'enterprise',category:'technology',sortOrder:7},{audience:'scientist',category:'technology',sortOrder:19}]};
test('existing role roundtrip retains associations and independent orders',()=>{reset(existing);role('scientist');assert.deepEqual(views(),existing.views);role('enterprise');assert.deepEqual(views(),existing.views);});
test('existing category roundtrip retains associations and orders',()=>{reset(existing);category('talent');category('technology');assert.deepEqual(views(),existing.views);});
test('edited primary/extra orders survive both kinds of roundtrip',()=>{reset(existing);control('primaryViewOrder').value=11;extra('scientist').order.value=23;assert.deepEqual(views().map(v=>v.sortOrder),[11,23]);role('scientist');role('enterprise');category('talent');category('technology');assert.deepEqual(views().map(v=>v.sortOrder),[11,23]);});
test('other role attributes survive and explicit clearing is saved',()=>{reset(existing);control('filter-enterpriseResearchStage').value='';category('talent');category('technology');assert.equal(snapshot().attributes.technologyField,'药物化学');assert.equal(snapshot().attributes.technologyMaturity,'小试');assert.equal(snapshot().attributes.enterpriseResearchStage,'');});
test('fresh direct role selection publishes only final role',()=>{reset({resourceType:'technology'});role('scientist');assert.deepEqual(viewRoles(),['scientist']);});
test('fresh category roundtrip then role selection publishes only final role',()=>{reset({resourceType:'technology'});category('talent');category('technology');role('scientist');assert.deepEqual(viewRoles(),['scientist']);});
test('fresh role switch inside another category does not retain provisional old role',()=>{reset({resourceType:'technology'});category('patent');role('scientist');category('technology');assert.deepEqual(viewRoles(),['scientist']);});
test('explicit extra association survives category and role changes',()=>{reset({resourceType:'technology'});extra('scientist').checkbox.checked=true;extra('scientist').checkbox.fire('change');extra('scientist').order.value=23;category('talent');category('technology');role('scientist');role('enterprise');assert.deepEqual(viewRoles(),['enterprise','scientist']);assert.equal(views().find(v=>v.audience==='scientist').sortOrder,23);});
test('intentional removal of existing extra association survives category roundtrip',()=>{reset(existing);extra('scientist').checkbox.checked=false;extra('scientist').checkbox.fire('change');category('talent');category('technology');assert.deepEqual(viewRoles(),['enterprise']);});
test('featured-only removes all submitted views',()=>{reset(existing);control('featuredOnly').checked=true;control('featuredOnly').fire('change');assert.deepEqual(views(),[]);});
test('old achievement keeps its type and attributes when opened from all resources',()=>{
 const old={id:'historical',resourceType:'achievement',attributes:{achievementType:'技术成果',technicalTrack:'小分子药物',achievementMaturity:'实验室小试阶段'},views:[{audience:'manager',category:'achievement',sortOrder:27}]};
 reset(old);assert.equal(control('resourceType').value,'achievement');assert.deepEqual(views(),old.views);
 assert.equal(snapshot().attributes.achievementType,'技术成果');assert.equal(snapshot().attributes.technicalTrack,'小分子药物');
 category('cro');category('achievement');assert.deepEqual(views(),old.views);assert.equal(snapshot().attributes.achievementType,'技术成果');
});
test('new service editor cannot select retired achievement and preserves category drafts',()=>{
 reset({resourceType:'cro',kind:'supply'});assert.ok(!control('resourceType').options.some(o=>o.value==='achievement'));
 control('filter-serviceCapability').value='CMC研究';category('cdmo');control('filter-serviceCapability').value='大规模细胞培养';category('cro');
 assert.equal(snapshot().attributes.serviceCapability,'CMC研究');assert.deepEqual(views(),[{audience:'manager',category:'cro',sortOrder:0}]);
 category('cdmo');assert.equal(snapshot().attributes.serviceCapability,'大规模细胞培养');
});
test('investor multi-value indications preserve old source attributes',()=>{
 reset({id:'old-project',resourceType:'project',attributes:{indications:['眼科'],researchStage:'1期临床',investorIndications:['肿瘤疾病','心血管系统']},views:[{audience:'investor',category:'project',sortOrder:1}]});
 assert.deepEqual(snapshot().attributes.investorIndications,['肿瘤疾病','心血管系统']);assert.deepEqual(snapshot().attributes.indications,['眼科']);
});
test('historical achievement without views remains historical on reopen',()=>{
 reset({id:'old-no-views',resourceType:'achievement',attributes:{achievementType:'技术成果',technicalTrack:'小分子药物'},views:[]});
 assert.equal(control('resourceAudience').value,'manager');assert.equal(control('resourceType').value,'achievement');
 assert.equal(snapshot().attributes.achievementType,'技术成果');assert.deepEqual(views(),[]);
});
test('pool editor retains custom industry and cooperation values',()=>{
 reset({id:'custom-pool',resourceType:'project',kind:'supply',industries:['生物医药','自定义行业'],cooperationModes:['自定义合作'],views:[{audience:'pool',category:'project',sortOrder:3}]});
 snapshot();assert.equal(control('industries').value,'生物医药，自定义行业');assert.equal(control('cooperationModes').value,'自定义合作');
});
if(failures)process.exitCode=1;
