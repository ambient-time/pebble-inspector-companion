'use strict';
const assert=require('assert');
const protocol=require('../signalApp/src/main/assets/signal-station/protocol.js');
function setup(ready=true){
 const h={messages:[],requests:[],timers:[],now:1000};
 h.client=protocol.createClient({now:()=>h.now,send:(p,done)=>{h.messages.push(p);done();},request:(method,url,body,done)=>{h.requests.push({method,url,body,done});return ()=>{};},setTimer:(fn,ms)=>{const t={fn,ms};h.timers.push(t);return t;},clearTimer:t=>t.cancelled=true});
 if(ready){h.client.handle({RequestType:'ready',HomeVersion:1,QuestionReviewVersion:1});last(h).done(null,{configured:true,home_version:1,question_review_version:1});}
 return h;
}
function last(h){return h.requests.at(-1);}
function commands(h,kind){return h.requests.filter(r=>r.url.endsWith('/question')&&r.body.kind===kind);}
function send(h,id,kind,fields={}){h.client.handle(Object.assign({RequestId:id,RequestType:kind,QuestionReviewVersion:1},fields));}
function draft(h){send(h,1,'question-open',{Prompt:'What changed?',QuestionContextKind:'capture',QuestionContextId:'capture-a'});last(h).done(null,{mode:'draft',draft_id:'draft',revision:1,home_mode:'none',text:'Question: What changed?\nCapture A only. Home: None.'});}
function review(h){draft(h);send(h,2,'question-review',{QuestionDraft:'draft',QuestionRevision:1});last(h).done(null,{mode:'review',draft_id:'draft',revision:1,home_mode:'none',review_id:'review',expires_at:1120,text:'Provider/model\nWhat changed?\nCapture A only. Home: None.'});}
let count=0;function test(name,fn){fn();count++;console.log('PASS '+name);}
test('old peer never starts unreviewed inference',()=>{
 const h=setup(false);h.client.handle({RequestId:1,RequestType:'ask',Prompt:'Question'});assert.equal(h.requests.length,0);assert(h.messages[0].StatusText.includes('phone'));
 send(h,2,'question-open',{Prompt:'Question',QuestionContextKind:'none'});assert.equal(h.requests.length,0);
});
test('replacement runtime challenges an open watch and refresh preserves healthy work',()=>{
 const h=setup(false);h.client.sync();last(h).done(null,{configured:true,home_version:1,question_review_version:1});
 assert.equal(h.messages.at(-1).Command,'ready-challenge');const session=h.messages.at(-1).BridgeSession;
 h.client.handle({RequestType:'ready',HomeVersion:1,QuestionReviewVersion:1});last(h).done(null,{configured:true,home_version:1,question_review_version:1});
 assert.equal(h.messages.at(-1).HomeVersion,1);assert.equal(h.messages.at(-1).QuestionReviewVersion,1);assert.equal(h.messages.at(-1).BridgeSession,session);assert(!h.messages.at(-1).Command);
});
test('exact capture context is explicit and review cannot invoke provider',()=>{
 const h=setup();review(h);assert.deepStrictEqual(commands(h,'question-open')[0].body,{kind:'question-open',request_id:1,prompt:'What changed?',context_kind:'capture',context_id:'capture-a'});
 assert(!h.requests.some(r=>r.url.endsWith('/start')||r.url.includes('/status')));assert.equal(h.messages.at(-1).QuestionReview,'review');
 h.timers.at(-1).fn();assert.equal(commands(h,'question-review').length,1);
});
test('one immutable confirmation, no forged prompt, ACK commits answer only',()=>{
 const h=setup();review(h);h.client.handle({RequestId:2,TextAck:1});assert(!h.requests.some(r=>r.url.endsWith('/delivered')));
 send(h,3,'question-send',{QuestionDraft:'draft',QuestionRevision:1,QuestionReview:'review',Prompt:'Forged'});
 assert.deepStrictEqual(last(h).body,{kind:'question-send',request_id:3,draft_id:'draft',revision:1,review_id:'review'});
 send(h,3,'question-send',{QuestionDraft:'draft',QuestionRevision:1,QuestionReview:'review'});assert.equal(commands(h,'question-send').length,1);
 last(h).done(null,{mode:'working'});last(h).done(null,{state:'ready',text:'Answer',record_id:'answer-a',record_kind:'answer'});
 assert.equal(h.messages.at(-1).RecordId,'answer-a');h.client.handle({RequestId:3,TextAck:1});assert(last(h).url.endsWith('/delivered'));
});
test('wrong binding, expiry and clock rollback cannot confirm',()=>{
 for(const extra of [{QuestionDraft:'other'},{QuestionRevision:2},{QuestionReview:'other'},{now:1120},{now:999}]){
  const h=setup();review(h);if(extra.now!==undefined)h.now=extra.now;
  send(h,3,'question-send',Object.assign({QuestionDraft:'draft',QuestionRevision:1,QuestionReview:'review'},extra));assert.equal(commands(h,'question-send').length,0);
 }
});
test('Home edits are explicit and invalidate an earlier review',()=>{
 const h=setup();review(h);send(h,3,'question-home',{QuestionDraft:'draft',QuestionRevision:1,QuestionHomeMode:'read',QuestionSystem:'home-a',QuestionSelected:1});
 assert.deepStrictEqual(last(h).body,{kind:'question-home',request_id:3,draft_id:'draft',revision:1,mode:'read',connection_id:'home-a',selected:true});
 last(h).done(null,{mode:'draft',draft_id:'draft',revision:2,home_mode:'read',text:'Home: Read. Home A selected.'});
 send(h,4,'question-send',{QuestionDraft:'draft',QuestionRevision:1,QuestionReview:'review'});assert.equal(commands(h,'question-send').length,0);
});
test('four-row systems preserve selection, reject duplicate/malformed rows',()=>{
 const h=setup();draft(h);send(h,2,'question-systems',{QuestionDraft:'draft',QuestionRevision:1,QuestionPage:0});
 last(h).done(null,{mode:'systems',draft_id:'draft',revision:1,home_mode:'none',page:0,pages:1,home_connections:[{id:'ha',name:'Home Assistant',selected:true}]});
 assert.equal(h.messages.at(-1).QuestionItems,'ha\t[x] Home Assistant');
 send(h,3,'question-systems',{QuestionDraft:'draft',QuestionRevision:1,QuestionPage:0});last(h).done(null,{mode:'systems',draft_id:'draft',revision:1,home_mode:'none',page:0,pages:1,home_connections:[{id:'ha',name:'A',selected:false},{id:'ha',name:'B',selected:false}]});
 assert(h.messages.at(-1).StatusText.includes('safely'));
});
test('nonmic exact handoff, oversized UTF8 refusal and cancel suppress late packets',()=>{
 const h=setup();send(h,1,'question-open',{Prompt:'',QuestionContextKind:'capture',QuestionContextId:'saved-a'});
 assert.equal(last(h).body.context_id,'saved-a');last(h).done(null,{mode:'phone',draft_id:'draft',revision:1,home_mode:'none',text:'Open this exact draft on your phone.'});assert.equal(h.messages.at(-1).QuestionMode,'phone');
 send(h,2,'question-review',{QuestionDraft:'draft',QuestionRevision:1});last(h).done(null,{mode:'review',draft_id:'draft',revision:1,home_mode:'none',review_id:'review',expires_at:1120,text:'界'.repeat(301)});assert(!h.messages.at(-1).QuestionReview);
 const c=setup();draft(c);send(c,2,'question-review',{QuestionDraft:'draft',QuestionRevision:1});const pending=last(c);
 send(c,3,'question-cancel',{QuestionDraft:'draft',QuestionRevision:1});const n=c.messages.length;
 pending.done(null,{mode:'review',draft_id:'draft',revision:1,home_mode:'none',review_id:'late',expires_at:1120,text:'Cancelled review'});assert.equal(c.messages.length,n);
 last(c).done(null,{mode:'phone',draft_id:'draft',revision:1,home_mode:'none',text:'Cancelled'});
 send(c,4,'question-send',{QuestionDraft:'draft',QuestionRevision:1,QuestionReview:'late'});assert.equal(commands(c,'question-send').length,0);
});
test('new question never inherits an earlier draft or Home selection',()=>{
 const h=setup();review(h);send(h,4,'question-open',{Prompt:'New question',QuestionContextKind:'none'});assert(!last(h).body.draft_id);assert(!last(h).body.context_id);assert(!last(h).body.mode);
});
test('native wake offer uses same exact review with replay protection',()=>{
 const h=setup(),offer={kind:'question-review',request_id:20,draft_id:'wake',revision:1,home_mode:'none',review_id:'wake-review',expires_at:1120,text:'Wake transcript\nNo attachments, memories, history or Home.'};
 h.client.configuration(offer);assert.equal(h.messages.at(-1).Command,'question-offer');const n=h.messages.length;h.client.configuration(offer);assert.equal(h.messages.length,n);
 send(h,21,'question-send',{QuestionDraft:'wake',QuestionRevision:1,QuestionReview:'wake-review'});assert.equal(last(h).body.review_id,'wake-review');assert(!h.requests.some(r=>r.url.endsWith('/confirm-wake')));
});
test('capture/history transport stays local and preserves exact saved identity',()=>{
 const h=setup();h.client.handle({RequestId:5,RequestType:'capture'});last(h).done(null,{});last(h).done(null,{state:'ready',text:'Saved',record_id:'capture-z',record_kind:'capture'});assert.equal(h.messages.at(-1).RecordId,'capture-z');
 h.client.handle({RequestId:6,RequestType:'history'});last(h).done(null,{text:'Recent records'});h.client.handle({RequestId:6,TextAck:1});assert(!h.requests.some(r=>r.url.endsWith('/delivered')));
});
console.log(count+' standalone reviewed-question protocol tests passed.');
