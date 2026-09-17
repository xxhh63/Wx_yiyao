'use strict';
// Executes the real editor and AI review entry without a browser or a server.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const repo=process.argv[2]||path.resolve(__dirname,'../..');
const source=fs.readFileSync(path.join(repo,'src/main/resources/static/admin/admin.js'),'utf8');
const catalogs=JSON.parse(fs.readFileSync(path.join(repo,'src/main/resources/catalog-schema.json'),'utf8'));
function between(start,end){const a=source.indexOf(start),b=source.indexOf(end,a);assert.ok(a>=0&&b>a,'source anchors still exist: '+start);return source.slice(a,b);}
class Node {
  constructor(tag){this.tag=tag;this.children=[];this.dataset={};this.handlers={};this._value='';this.hidden=false;this.className='';this.classList={add:()=>{},toggle:()=>{}};}
  append(...nodes){for(const node of nodes){this.children.push(node);node.parentElement=this;}}
  replaceChildren(...nodes){this.children=[];this.append(...nodes);}
  addEventListener(name,fn){(this.handlers[name]??=[]).push(fn);}
  fire(name){for(const fn of this.handlers[name]||[])fn({target:this});}
  setAttribute(name,value){this[name]=String(value);}
  showModal(){this.open=true;}
  close(){this.open=false;}
  focus(){}
  get options(){return this.children;}
  get value(){return this.tag==='select'?(this.children.find(x=>x.selected)?.value??(this.multiple?'':this.children[0]?.value)??''):this._value;}
  set value(value){value=String(value);if(this.tag==='select'){for(const item of this.children)item.selected=item.value===value;}else this._value=value;}
  get selectedOptions(){return this.children.filter(x=>x.selected);}
  closest(selector){let node=this;while(node){if(selector[0]==='.'?node.className.split(' ').includes(selector.slice(1)):node.tag===selector)return node;node=node.parentElement;}return null;}
  querySelectorAll(selector){const all=this.children.flatMap(x=>[x,...x.querySelectorAll('*')]);if(selector==='*')return all;if(selector==='[data-resource-field]')return all.filter(x=>x.dataset.resourceField);const match=selector.match(/^\[name=([^\]]+)\](:checked)?$/);if(match)return all.filter(x=>x.name===match[1]&&(!match[2]||x.checked));throw new Error('Unsupported selector '+selector);}
  querySelector(selector){return this.querySelectorAll(selector)[0]||null;}
}
const ids=new Map(),requests=[];
let apiHandler=async()=>{throw new Error('AI review must not call an API');};
const state={catalogs,resourceAudience:'investor',editor:null,ai:null,editorSequence:0};
const get=id=>ids.get(id)||ids.get('editor-fields')?.querySelectorAll('*').find(x=>x.id===id);
function reset(collection,draft={}){
  ids.clear();for(const id of ['editor-title','editor-fields','editor-form','editor-dialog','publication-notice','editor-status','editor-error','reload-editor','ai-review-notice','return-ai','ai-dialog','ai-title','ai-text','ai-file','ai-file-label','ai-file-row','ai-context','ai-audience','ai-category','ai-error','ai-status','ai-submit','ai-cancel','ai-history','ai-file-mode','ai-text-mode','ai-resume','ai-examples','ai-input-label'])ids.set(id,new Node('div'));
  ids.get('editor-form').elements={namedItem:name=>get('editor-fields').querySelector('[name='+name+']')};
  state.editor={collection,item:{publicationStatus:'DRAFT',sortOrder:0,...draft},aiDraft:true,dirty:true,busy:false};
  state.ai={collection,mode:'text',text:'',file:null,previousDraft:{},messages:[],context:{audience:'',resourceType:''},status:{configured:true,maxFileBytes:5242880,maxTextChars:60000,supportedFormats:['txt','pdf']},sequence:0,busy:false};requests.length=0;
  apiHandler=async()=>{throw new Error('AI review must not call an API');};
  if(typeof context!=='undefined')context.window.confirm=()=>true;
}
const context=vm.createContext({URL,URLSearchParams,FormData,AbortController,setTimeout,clearTimeout,Map,Set,state,document:{createElement:tag=>new Node(tag)},$:get,window:{confirm:()=>true},
  api:async(...args)=>{requests.push(args);return apiHandler(...args);},ensureCatalogs:async()=>{},canDiscard:()=>true,
  singular:{featured:'主推',promos:'广告',policies:'资讯',resources:'资源',institutions:'机构'},
  tones:[['medical','医药青绿'],['blue','明亮蓝']],audiences:[['pool','项目池'],['investor','投资人'],['enterprise','企业'],['scientist','科研'],['manager','服务机构']]});
const helpers=source.slice(0,source.indexOf("if (typeof document !== 'undefined')"));
const review=source.includes('  async function showAiReview(')?between('  async function showAiReview(','  function renderEditor('):'';
const assistant=source.includes('  function aiHasWork(')?between('  function aiHasWork(','  async function openEditor('):'';
vm.runInContext(helpers+'\nlet controlId=0;\n'+between('  function el(','  async function api(')+between('  function field(','  async function ensureCatalogs(')+assistant+review+between('  function renderEditor(',"  $('editor-form').addEventListener('input'")+ '\nglobalThis.render=renderEditor;globalThis.read=readEditor;globalThis.review=typeof showAiReview === "function"?showAiReview:null;globalThis.submit=typeof submitAi === "function"?submitAi:null;globalThis.cancel=typeof cancelAiRequest === "function"?cancelAiRequest:null;',context);
function control(name){const node=get('editor-form').elements.namedItem(name);assert.ok(node,'control exists: '+name);return node;}
let failures=0;
async function test(name,fn){try{await fn();console.log('PASS '+name);}catch(error){failures++;console.error('FAIL '+name+'\n'+error.stack);}}
(async()=>{
  await test('empty AI resource leaves factual selects and classification blank',()=>{
    reset('resources');context.render();
    for(const name of ['kind','publisherRole','status','tone','resourceAudience','resourceType'])assert.equal(control(name).value,'',name+' must remain unknown');
    const draft=JSON.parse(JSON.stringify(context.read(false)));assert.equal(draft.amountWan,null);assert.deepEqual(draft.views,[]);assert.deepEqual(draft.attributes,{});
  });
  await test('empty AI policy and promo do not invent source category button or action',()=>{
    reset('policies');context.render();for(const name of ['category','sourceKind','date'])assert.equal(control(name).value,'',name);assert.equal(control('homeRecommended').checked,false);
    reset('promos');context.render();for(const name of ['buttonText','tone','actionType'])assert.equal(control(name).value,'',name);
  });
  await test('AI review only opens a new draft and never saves or publishes',async()=>{
    assert.equal(typeof context.review,'function','AI review entry exists');reset('resources');
    await context.review({collection:'resources',draft:{id:'online-id',version:9,publicationStatus:'PUBLISHED',title:'待审核材料'},missingFields:['kind'],warnings:[]});
    assert.equal(state.editor.item.id,undefined);assert.equal(state.editor.item.version,undefined);assert.equal(state.editor.item.publicationStatus,'DRAFT');assert.equal(state.editor.dirty,true);assert.equal(get('editor-dialog').open,true);assert.equal(control('title').value,'待审核材料');assert.equal(requests.length,0);
  });
  await test('one chat click posts only to the AI endpoint and opens review',async()=>{
    assert.equal(typeof context.submit,'function','AI submit entry exists');reset('policies');state.editor=null;get('ai-text').value='文件写明标题为政策通知';
    apiHandler=async()=>({collection:'policies',draft:{title:'政策通知'},missingFields:['date'],warnings:[]});
    await context.submit({preventDefault(){}});assert.equal(requests.length,1);assert.equal(requests[0][0],'ai/draft');
    const body=JSON.parse(requests[0][1].body);assert.equal(body.text,'文件写明标题为政策通知');assert.deepEqual(body.previousDraft,{});assert.equal(state.editor.item.publicationStatus,'DRAFT');
  });
  await test('AI failure preserves entered text with no retry',async()=>{
    assert.equal(typeof context.submit,'function');reset('institutions');state.editor=null;get('ai-text').value='保留这段原文';apiHandler=async()=>{throw new Error('服务暂不可用');};
    await context.submit({preventDefault(){}});assert.equal(requests.length,1);assert.equal(get('ai-text').value,'保留这段原文');assert.equal(state.ai.text,'保留这段原文');assert.match(get('ai-error').textContent,/服务暂不可用/);
  });
  await test('cancelled request cannot replace newer input when its response arrives',async()=>{
    assert.equal(typeof context.submit,'function');reset('institutions');state.editor=null;get('ai-text').value='旧内容';let finish;
    apiHandler=()=>new Promise(resolve=>{finish=resolve;});const pending=context.submit({preventDefault(){}});
    assert.equal(typeof finish,'function');context.cancel();get('ai-text').value='新内容';state.ai.text='新内容';
    finish({collection:'institutions',draft:{name:'旧结果'},warnings:[]});await pending;assert.equal(state.editor,null);assert.equal(get('ai-text').value,'新内容');assert.equal(requests.length,1);
  });
  await test('staff classification chosen in the assistant survives submitting an existing review',async()=>{
    reset('resources');context.render();state.ai.context={audience:'scientist',resourceType:'patent'};get('ai-text').value='补充专利资料';
    apiHandler=async()=>({collection:'resources',draft:{title:'专利资料'},warnings:[]});await context.submit({preventDefault(){}});
    const request=JSON.parse(requests[0][1].body);assert.equal(request.context.audience,'scientist');assert.equal(request.context.resourceType,'patent');
  });
  await test('file submission carries request JSON without any save endpoint',async()=>{
    reset('institutions');state.editor=null;state.ai.mode='file';state.ai.file=new File(['机构材料'],'资料.txt',{type:'text/plain'});
    apiHandler=async()=>({collection:'institutions',draft:{name:'机构'},warnings:[]});await context.submit({preventDefault(){}});
    assert.equal(requests.length,1);assert.equal(requests[0][0],'ai/draft');const form=requests[0][1].body;assert.ok(form instanceof FormData);assert.equal(form.get('file').name,'资料.txt');assert.equal(JSON.parse(form.get('request')).collection,'institutions');
  });
  await test('declining replacement keeps the current review with no request',async()=>{
    reset('institutions');context.render();control('name').value='工作人员已修改';get('ai-text').value='另一份资料';context.window.confirm=()=>false;
    await context.submit({preventDefault(){}});assert.equal(requests.length,0);assert.equal(control('name').value,'工作人员已修改');
  });
  await test('AI cannot invent image target or home recommendation choices',()=>{
    const {aiDraftItem}=require(path.join(repo,'src/main/resources/static/admin/admin.js'));
    assert.equal(aiDraftItem('resources',{imageUrl:'https://invented.example/image.png'}).imageUrl,'');
    assert.equal(aiDraftItem('policies',{homeRecommended:true}).homeRecommended,false);
    assert.equal(aiDraftItem('promos',{action:{type:'resource',targetId:'made-up-id'}}).action.targetId,'');
    const previous={imageUrl:'https://staff.example/cover.png',action:{type:'resource',targetId:'staff-choice'}};
    const item=aiDraftItem('promos',{action:{type:'resource',targetId:'made-up-id'}},previous);assert.equal(item.imageUrl,previous.imageUrl);assert.equal(item.action.targetId,'staff-choice');
  });
  await test('an explicitly chosen role survives an unknown type without a default view',()=>{
    reset('resources');state.ai.context.audience='scientist';context.render();assert.equal(control('resourceAudience').value,'scientist');assert.equal(control('resourceType').value,'');assert.deepEqual(JSON.parse(JSON.stringify(context.read(false))).views,[]);
  });
  await test('visible transaction choice synchronizes the hidden root field before native validation',()=>{
    reset('resources',{resourceType:'project',views:[{audience:'investor',category:'project',sortOrder:0}]});context.render();
    const filter=control('filter-kind');assert.equal(control('kind').required,false);assert.equal(filter.required,true);filter.value='demand';filter.fire('change');assert.equal(control('kind').value,'demand');assert.equal(context.read(false).kind,'demand');
  });
  await test('unconfigured service and unsupported files never issue a draft request',async()=>{
    reset('institutions');state.editor=null;state.ai.status.configured=false;get('ai-text').value='机构资料';await context.submit({preventDefault(){}});assert.equal(requests.length,0);assert.match(get('ai-error').textContent,/服务器尚未配置DEEPSEEK_API_KEY/);
    state.ai.status.configured=true;state.ai.mode='file';state.ai.file=new File(['bad'],'archive.zip');await context.submit({preventDefault(){}});assert.equal(requests.length,0);assert.match(get('ai-error').textContent,/暂不支持/);
  });
  await test('refinement preserves staff sort values and ignores model sort metadata',()=>{
    const {aiDraftItem}=require(path.join(repo,'src/main/resources/static/admin/admin.js'));
    const draft={sortOrder:999,views:[{audience:'investor',category:'project',sortOrder:999,resourceId:'invented'}]};
    const previous={sortOrder:17,views:[{audience:'investor',category:'project',sortOrder:23}]};const item=aiDraftItem('resources',draft,previous);
    assert.equal(item.sortOrder,17);assert.deepEqual(item.views,previous.views);assert.equal(aiDraftItem('resources',draft).views[0].sortOrder,0);
  });
  await test('only nonnegative safe staff sort integers and boolean recommendations are restored',()=>{
    const {aiDraftItem}=require(path.join(repo,'src/main/resources/static/admin/admin.js'));
    for(const value of [-1,1.5,Number.MAX_SAFE_INTEGER+1,'12']){
      const item=aiDraftItem('resources',{views:[{audience:'investor',category:'project'}]},{sortOrder:value,views:[{audience:'investor',category:'project',sortOrder:value}]});
      assert.equal(item.sortOrder,0);assert.equal(item.views[0].sortOrder,0);
    }
    assert.equal(aiDraftItem('policies',{}, {homeRecommended:'false'}).homeRecommended,false);
  });
  await test('policy refinement keeps manual sort and recommendation choices through the review round trip',async()=>{
    reset('policies');await context.review({collection:'policies',draft:{title:'政策材料',sortOrder:999,homeRecommended:true},warnings:[]});
    assert.equal(control('sortOrder').value,'0');assert.equal(control('homeRecommended').checked,false);
    for(const recommended of [true,false]){
      control('sortOrder').value='37';control('homeRecommended').checked=recommended;get('ai-text').value='补充政策原文';
      apiHandler=async()=>({collection:'policies',draft:{title:'政策材料补充',sortOrder:999,homeRecommended:!recommended},warnings:[]});
      await context.submit({preventDefault(){}});
      const request=JSON.parse(requests.at(-1)[1].body);assert.equal(request.previousDraft.sortOrder,37);assert.equal(request.previousDraft.homeRecommended,recommended);
      assert.equal(control('sortOrder').value,'37');assert.equal(control('homeRecommended').checked,recommended);assert.equal(state.editor.item.publicationStatus,'DRAFT');
    }
    assert.equal(requests.length,2);assert.ok(requests.every(([url])=>url==='ai/draft'));
  });
  await test('an unfinished staff image upload blocks refinement without losing input',async()=>{
    reset('resources');state.editor=null;state.uploads=1;get('ai-text').value='补充说明';await context.submit({preventDefault(){}});
    assert.equal(requests.length,0);assert.equal(get('ai-text').value,'补充说明');assert.match(get('ai-error').textContent,/图片仍在上传/);state.uploads=0;
  });
  await test('AI source notes and all existing source kinds survive review and serialization',async()=>{
    reset('institutions');await context.review({collection:'institutions',draft:{name:'来源可核对',sourceKind:'legacy_sample',sourceNote:'材料明确说明为迁入示例资料'},warnings:[]});
    assert.equal(control('sourceKind').value,'legacy_sample');assert.equal(control('sourceNote').value,'材料明确说明为迁入示例资料');
    control('sourceNote').value='工作人员核对后的来源说明';assert.equal(context.read(false).sourceNote,'工作人员核对后的来源说明');
  });
  if(failures)process.exitCode=1;
})();
