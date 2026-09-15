'use strict';
const assert=require('assert'), fs=require('fs'), path=require('path');
const protocol=require('../signalApp/src/main/assets/signal-station/protocol.js');
function setup(negotiated=true){
 const h={messages:[],requests:[],timers:[],now:1000};
 h.client=protocol.createClient({now:()=>h.now,send:(p,done)=>{h.messages.push(p);done();},request:(method,url,body,done)=>{h.requests.push({method,url,body,done});return ()=>{};},setTimer:(fn,ms)=>{const t={fn,ms};h.timers.push(t);return t;},clearTimer:t=>t.cancelled=true});
 if(negotiated){h.client.handle({RequestType:'ready',HomeVersion:1});h.requests.at(-1).done(null,{home_version:1});}
 return h;
}
function send(h,id,kind,extra={}){h.client.handle(Object.assign({RequestId:id,RequestType:kind,HomeVersion:1},extra));}
function last(h){return h.requests.at(-1);}
function nativeHomes(h,kind){return h.requests.filter(r=>r.url.endsWith('/home')&&r.body.kind===kind);}
function review(h,id=10){send(h,id,'home-review',{HomeFavorite:'fav',HomeAction:'on'});last(h).done(null,{mode:'review',favorite_id:'fav',action_id:'on',intent_id:'nonce',expires_at:1120,text:'Turn Kitchen lamp on. Target: kitchen-lamp; state: on.'});}
let count=0;function test(name,fn){fn();count++;console.log('PASS '+name);}
test('Home requires both watch and native capabilities, keeps legacy handshake unchanged',()=>{
 const h=setup(false);h.client.sync();last(h).done(null,{home_version:1,configured:true,token:'SECRET'});assert(!('HomeVersion' in h.messages[0]));send(h,1,'home-list',{HomePage:0});assert(!nativeHomes(h,'home-list').length);
 h.client.handle({RequestType:'ready',HomeVersion:1});last(h).done(null,{home_version:0});send(h,2,'home-list',{HomePage:0});assert(!nativeHomes(h,'home-list').length);
 assert(!JSON.stringify(h.messages).includes('SECRET'));
});
test('pages expose only favorites and ACK/retry never repeats native operation',()=>{
 const h=setup();send(h,1,'home-list',{HomePage:0});assert.deepStrictEqual(last(h).body,{kind:'home-list',request_id:1,page:0});last(h).done(null,{mode:'list',page:0,pages:2,items:[{id:'fav',label:'Kitchen lamp',credentials:'SECRET'}],catalog:'SECRET'});
 assert.equal(h.messages.at(-1).HomeItems,'fav\tKitchen lamp');h.timers.at(-1).fn();assert.equal(nativeHomes(h,'home-list').length,1);
 h.client.handle({RequestId:1,TextAck:1});assert(h.timers.at(-1).cancelled);assert(!h.requests.some(r=>r.url.endsWith('/delivered')));assert(!JSON.stringify(h.messages).includes('SECRET'));
});
test('exact review confirms once by immutable identifiers with no params',()=>{
 const h=setup();review(h);h.client.handle({RequestId:10,TextAck:1});send(h,11,'home-confirm',{HomeFavorite:'fav',HomeAction:'on',HomeIntent:'nonce',Prompt:'untrusted params'});
 assert.deepStrictEqual(last(h).body,{kind:'home-confirm',request_id:11,favorite_id:'fav',action_id:'on',intent_id:'nonce'});send(h,11,'home-confirm',{HomeFavorite:'fav',HomeAction:'on',HomeIntent:'nonce'});assert.equal(nativeHomes(h,'home-confirm').length,1);assert.equal(nativeHomes(h,'home-cancel').length,0);
 last(h).done(null,{mode:'result',favorite_id:'fav',text:'Done.'});assert.equal(h.messages.at(-1).HomeMode,'result');
});
test('wrong favorite/action/intent, expired and clock rollback confirmations never dispatch',()=>{
 for(const patch of [{HomeFavorite:'other'},{HomeAction:'off'},{HomeIntent:'other'},{now:1120},{now:999}]){
  const h=setup();review(h);if(patch.now!==undefined)h.now=patch.now;send(h,11,'home-confirm',Object.assign({HomeFavorite:'fav',HomeAction:'on',HomeIntent:'nonce'},patch));assert.equal(nativeHomes(h,'home-confirm').length,0);
 }
});
test('cancel discards late review and prevents its confirmation',()=>{
 const h=setup();send(h,10,'home-review',{HomeFavorite:'fav',HomeAction:'on'});const pending=last(h);send(h,10,'home-cancel',{HomeFavorite:'fav',HomeAction:'on'});const n=h.messages.length;
 pending.done(null,{mode:'review',favorite_id:'fav',action_id:'on',intent_id:'nonce',expires_at:1120,text:'Turn on.'});assert.equal(h.messages.length,n);send(h,11,'home-confirm',{HomeFavorite:'fav',HomeAction:'on',HomeIntent:'nonce'});assert(!nativeHomes(h,'home-confirm').length);
});
test('oversized review and labels hand off whole instead of truncating',()=>{
 const h=setup();send(h,10,'home-review',{HomeFavorite:'fav',HomeAction:'on'});last(h).done(null,{mode:'review',favorite_id:'fav',action_id:'on',intent_id:'nonce',expires_at:1120,text:'界'.repeat(301)});assert.equal(h.messages.at(-1).HomeMode,'handoff');assert(!h.messages.at(-1).HomeIntent);assert(!h.messages.at(-1).HomeAction);
 send(h,11,'home-list',{HomePage:0});last(h).done(null,{mode:'list',page:0,pages:1,items:[{id:'fav',label:'界'.repeat(34)}]});assert.equal(h.messages.at(-1).HomeMode,'handoff');
});
test('standing grant result bypasses watch confirmation without fabricating intent',()=>{
 const h=setup();send(h,10,'home-review',{HomeFavorite:'fav',HomeAction:'on'});last(h).done(null,{mode:'result',favorite_id:'fav',text:'Executed under your existing permission.'});assert.equal(h.messages.at(-1).HomeMode,'result');assert(!h.messages.at(-1).HomeIntent);
});
test('invalid pages, duplicate IDs, changed response binding fail safely',()=>{
 for(const data of [{mode:'list',page:1,pages:2,items:[]},{mode:'list',page:0,pages:1,items:[{id:'a',label:'A'},{id:'a',label:'B'}]},{mode:'detail',favorite_id:'wrong',text:'Wrong'}]){
  const h=setup();send(h,10,data.mode==='list'?'home-list':'home-open',{HomePage:0,HomeFavorite:'fav'});last(h).done(null,data);assert.equal(h.messages.at(-1).HomeMode,'result');assert(!h.messages.at(-1).HomeIntent);
 }
});
test('ready after prior Home negotiation with an old watch disables Home',()=>{
 const h=setup();h.client.handle({RequestType:'ready'});last(h).done(null,{home_version:1});send(h,10,'home-list',{HomePage:0});assert(!nativeHomes(h,'home-list').length);
});
test('wire keys preserve all existing numeric values and append Home',()=>{
 const keys=require('../signalApp/src/main/assets/signal-station/message-keys.json');const names=['RequestType','RequestId','Prompt','SpeakerAvailable','Muted','ResponseText','StatusText','AudioExpected','AudioBegin','AudioChunk','AudioEnd','AudioAck','AudioSequence','Demo','Configured','VoiceEnabled','Volume','Snapshot','Command','Enabled','Complete','TextAck','ConfirmTranscript','ReducedMotion','BridgeReady','HomeVersion','HomePage','HomePages','HomeFavorite','HomeIntent','HomeAction','HomeExpires','HomeItems','HomeMode'];names.forEach((k,i)=>assert.equal(keys[k],10000+i));
});
// Exercise existing protocol regressions against the actual standalone asset.
const watchTests=process.argv[2];
if(watchTests){const file=path.resolve(watchTests);new Function('require','__dirname',fs.readFileSync(file,'utf8'))(p=>p==='../src/pkjs/protocol'?protocol:require(p),path.dirname(file));}
console.log(count+' standalone Home protocol tests passed.');
