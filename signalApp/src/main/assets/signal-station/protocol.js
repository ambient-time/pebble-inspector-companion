'use strict';
// Signal Station native bridge. Provider keys never enter this runtime.
var BASE = 'https://field-inspector.invalid/native/v1/';
var MAX_TEXT_BYTES = 900;
function utf8Bytes(s) {
  try { return unescape(encodeURIComponent(s)).length; } catch (_) { return Infinity; }
}
function requestId(id) { return typeof id === 'number' && id > 0 && id <= 2147483647 && Math.floor(id) === id; }
function createClient(options) {
  var active = null, generation = 0, reviewedIds = [], homeVersion = 0, homeReady = false, homeSeen = [];
  var questionVersion=0, questionReady=false, questionSeen=[], questionDraft=null;
  var bridgeSession=String(Date.now())+'-'+Math.random().toString(36).slice(2,12);
  var now = options.now || function () { return Math.floor(Date.now()/1000); };
  var later = options.setTimer || setTimeout, clear = options.clearTimer || clearTimeout;
  function send(packet, done, failed) {
    var owner = generation;
    options.send(packet, done || function () {}, failed || function () {}, function () { return owner === generation; });
  }
  function native(method, path, body, done) { return options.request(method, BASE + path, body, done || function () {}); }
  function valid(a) { return active === a && a.generation === generation; }
  function cancel(fromNative) {
    var old = active;
    generation++; active = null;
    if (old) {
      if (old.timer) clear(old.timer);
      if (old.abort) old.abort();
      // XHR abort alone does not cancel a native coroutine.
      if (!fromNative && old.question && !old.questionSent) { /* Draft lifetime is explicit; navigation is not cancellation. */ }
      else if (!fromNative && old.home) native('POST', 'home', Object.assign({},old.home,{kind:'home-cancel'}));
      else if (!fromNative && !old.history) native('POST', 'cancel', {request_id:old.id});
    }
  }
  function error(a, message) {
    if (!valid(a)) return;
    if (a.timer) clear(a.timer);
    a.terminal = true;
    if (a.home) return send({RequestId:a.id,Command:'home',HomeVersion:1,HomeMode:'result',HomeFavorite:a.home.favorite_id || '',ResponseText:'Home request unavailable. Check the phone.',Complete:1});
    send({RequestId:a.id, StatusText:message || 'Open Signal Station on your phone.', Complete:1});
  }
  function deliver(a) {
    if (!valid(a) || a.delivered) return;
    if (++a.deliveries > 3) return error(a, 'Watch delivery timed out. Ask again when connected.');
    send(a.questionPacket || a.homePacket || Object.assign({RequestId:a.id, ResponseText:a.text, Complete:1},a.record || {}), function () {
      if (valid(a) && !a.delivered) a.timer = later(function () { deliver(a); }, 3000);
    }, function () { error(a, 'Watch connection lost.'); });
  }
  function acknowledge(a, attempt) {
    if (!valid(a)) return;
    a.committing = true;
    native('POST', 'delivered', {request_id:a.id}, function (err) {
      if (!valid(a)) return;
      if (!err) { a.delivered = true; a.committing = false; return; }
      if (attempt < 3) a.timer = later(function () { acknowledge(a, attempt + 1); }, 500);
      else { a.committing = false; error(a, 'Reply received; session acknowledgement failed.'); }
    });
  }
  function drainWatchData(a) {
    if (!valid(a) || a.uploading || !a.watchQueue.length) return;
    a.uploading = true;
    var packet = a.watchQueue.shift();
    native('POST', 'watch-data', packet, function (err) {
      if (!valid(a)) return;
      a.uploading = false;
      if (err) return error(a, err);
      drainWatchData(a);
    });
  }
  function poll(a) {
    if (!valid(a)) return;
    if (++a.polls > 180) return error(a, 'Request timed out. Back stops this request.');
    a.abort = native('GET', 'status?request_id=' + a.id, null, function (err, data) {
      if (!valid(a)) return;
      if (err || !data) return error(a, err);
      if (data.state === 'error') return error(a, data.status || 'Request failed. Check the phone.');
      if (data.state === 'ready') {
        if (typeof data.text !== 'string' || !data.text.trim() || utf8Bytes(data.text) > MAX_TEXT_BYTES) {
          return error(a, 'Invalid report size. Check the phone.');
        }
        a.text = data.text; a.terminal = true; a.deliveries = 0;
        a.record=homeId(data.record_id) && ['capture','answer'].indexOf(data.record_kind)>=0 ? {RecordId:data.record_id,RecordKind:data.record_kind} : {};
        return deliver(a);
      }
      if (data.state !== 'working') return error(a, 'Unknown companion response.');
      if (data.status && data.status !== a.lastStatus) {
        a.lastStatus = data.status;
        send({RequestId:a.id, StatusText:String(data.status).slice(0,90), Complete:0});
      }
      a.timer = later(function () { poll(a); }, 500);
    });
  }
  function attach(id) {
    if (active && active.id === id) return active;
    cancel();
    active = {id:id, generation:generation, timer:null, abort:null, polls:0, terminal:false, delivered:false, watchQueue:[]};
    return active;
  }
  function sync() {
    native('GET', 'capabilities', null, function (err, cfg) {
      if (err || !cfg) { homeReady=false; questionReady=false; return send({BridgeReady:0, Configured:0, HomeVersion:0, QuestionReviewVersion:0, StatusText:'Open Signal Station on your phone.'}); }
      homeReady=homeVersion===1 && cfg.home_version===1;
      questionReady=questionVersion===1 && cfg.question_review_version===1;
      var packet={BridgeReady:1, Configured:cfg.configured ? 1 : 0, Enabled:JSON.stringify(cfg.enabled || []),
        ConfirmTranscript:cfg.confirmTranscript ? 1 : 0, ReducedMotion:cfg.reducedMotion ? 1 : 0,
        BridgeSession:bridgeSession, QuestionReviewVersion:questionReady?1:0};
      // A replacement runtime has forgotten the watch capabilities. Challenge it
      // rather than waiting for the watch app to close and reopen.
      if (!homeVersion && !questionVersion) packet.Command='ready-challenge';
      if (homeVersion===1) packet.HomeVersion=homeReady?1:0;
      send(packet);
    });
  }
  function homeId(value) { return typeof value==='string' && value.length>0 && utf8Bytes(value)<=64 && !/[\x00-\x1f\x7f]/.test(value); }
  function homeText(value,max) { return typeof value==='string' && value.trim().length>0 && utf8Bytes(value)<=max && value.indexOf('\0')<0; }
  function homePacket(a,data) {
    var packet={RequestId:a.id,Command:'home',HomeVersion:1,Complete:1}, body=a.home;
    function handoff() { return Object.assign(packet,{HomeMode:'handoff',HomeFavorite:body.favorite_id || '',ResponseText:'Review the complete Home item on your phone.'}); }
    if (!data || ['list','detail','review','handoff','result'].indexOf(data.mode)<0) return null;
    packet.HomeMode=data.mode;
    if (data.mode==='list') {
      if (body.kind!=='home-list' || !Number.isInteger(data.page) || data.page!==body.page || !Number.isInteger(data.pages) || data.pages<1 || data.pages>1000 || data.page>=data.pages || !Array.isArray(data.items) || data.items.length>4) return null;
      if (!data.items.length && (data.page!==0 || data.pages!==1)) return null;
      var ids=[],rows=[];
      for (var i=0;i<data.items.length;i++) {
        var item=data.items[i];
        if (!item || !homeId(item.id) || !homeText(item.label,100) || /[\x00-\x1f\x7f]/.test(item.label)) return handoff();
        if (ids.indexOf(item.id)>=0) return null;
        ids.push(item.id); rows.push(item.id+'\t'+item.label);
      }
      packet.HomeItems=rows.join('\n'); if (utf8Bytes(packet.HomeItems)>700) return handoff();
      packet.HomePage=data.page; packet.HomePages=data.pages; return packet;
    }
    if ((data.favorite_id || '')!==(body.favorite_id || '')) return null;
    packet.HomeFavorite=body.favorite_id || '';
    if (!homeText(data.text,900)) return handoff();
    packet.ResponseText=data.text;
    if (data.mode==='detail') {
      if (body.kind!=='home-open') return null;
      if (data.action_id!==undefined && data.action_id!==null) { if (!homeId(data.action_id)) return handoff(); packet.HomeAction=data.action_id; }
    }
    if (data.mode==='review') {
      if (body.kind!=='home-review' || data.action_id!==body.action_id || !homeId(data.intent_id) || !Number.isInteger(data.expires_at) || data.expires_at<=now() || data.expires_at-now()>120) return null;
      packet.HomeAction=data.action_id; packet.HomeIntent=data.intent_id; packet.HomeExpires=data.expires_at;
    }
    return packet;
  }
  function handleHome(p) {
    var kind=p.RequestType;
    if (p.HomeVersion!==1 || !homeReady || ['home-list','home-open','home-review','home-confirm','home-cancel','home-phone'].indexOf(kind)<0) return;
    if ((kind==='home-review'||kind==='home-confirm') && !questionReady) return send({RequestId:p.RequestId,Command:'home',HomeVersion:1,HomeMode:'handoff',HomeFavorite:homeId(p.HomeFavorite)?p.HomeFavorite:'',ResponseText:'Update the watch for complete action review, or continue on your phone.',Complete:1});
    var body={kind:kind,request_id:p.RequestId};
    if (kind==='home-list') { if (!Number.isInteger(p.HomePage)||p.HomePage<0||p.HomePage>=1000) return; body.page=p.HomePage; }
    else {
      if (p.HomeFavorite!==undefined) { if (!homeId(p.HomeFavorite)) return; body.favorite_id=p.HomeFavorite; }
      if (['home-open','home-review','home-confirm'].indexOf(kind)>=0 && !body.favorite_id) return;
      if (p.HomeAction!==undefined) { if (!homeId(p.HomeAction)) return; body.action_id=p.HomeAction; }
      if (p.HomeIntent!==undefined) { if (!homeId(p.HomeIntent)) return; body.intent_id=p.HomeIntent; }
      if (['home-review','home-confirm'].indexOf(kind)>=0 && !body.action_id) return;
      if (kind==='home-confirm' && !body.intent_id) return;
    }
    if (kind==='home-cancel') {
      if (active && active.home && active.id===p.RequestId) cancel(true);
      native('POST','home',body); return;
    }
    if (active && active.id===p.RequestId) return;
    if (homeSeen.indexOf(p.RequestId)>=0) return;
    var previous=active, review=previous && previous.homePacket;
    if (kind==='home-confirm' && (!review || review.HomeMode!=='review' || previous.confirmed || review.HomeFavorite!==body.favorite_id || review.HomeAction!==body.action_id || review.HomeIntent!==body.intent_id || review.HomeExpires<=now() || review.HomeExpires-now()>120)) return;
    if (kind==='home-confirm') { previous.confirmed=true; cancel(true); }
    var a=attach(p.RequestId); a.home=body;
    homeSeen.push(p.RequestId); if (homeSeen.length>64) homeSeen.shift();
    a.abort=native('POST','home',body,function(err,data) {
      if (!valid(a)) return;
      if (err) return error(a,err);
      a.homePacket=homePacket(a,data);
      if (!a.homePacket) return error(a,'Invalid Home response.');
      a.text=a.homePacket.ResponseText || a.homePacket.HomeItems || 'Home';
      a.terminal=true; a.deliveries=0; deliver(a);
    });
  }
  function questionPacket(a,data) {
    if (!data || !homeId(data.draft_id) || !Number.isInteger(data.revision) || data.revision<1 || ['none','read','actions'].indexOf(data.home_mode)<0) return null;
    var packet={RequestId:a.id,Command:'question',QuestionReviewVersion:1,QuestionMode:data.mode,
      QuestionDraft:data.draft_id,QuestionRevision:data.revision,QuestionHomeMode:data.home_mode,Complete:1};
    if (questionDraft && a.question.kind!=='question-open' && data.draft_id!==a.question.draft_id) return null;
    if (data.mode==='systems') {
      if (!Number.isInteger(data.page)||!Number.isInteger(data.pages)||data.page<0||data.pages<1||data.pages>1000||data.page>=data.pages||!Array.isArray(data.home_connections)||data.home_connections.length>4) return null;
      var ids=[], rows=[];
      for(var i=0;i<data.home_connections.length;i++) {
        var system=data.home_connections[i];
        if (!system || !homeId(system.id)||!homeText(system.name,96)||/[\x00-\x1f\x7f]/.test(system.name)||typeof system.selected!=='boolean'||ids.indexOf(system.id)>=0) return null;
        ids.push(system.id); rows.push(system.id+'\t'+(system.selected?'[x] ':'[ ] ')+system.name);
      }
      packet.QuestionItems=rows.join('\n'); packet.QuestionPage=data.page; packet.QuestionPages=data.pages;
      if (utf8Bytes(packet.QuestionItems)>700) return null;
    } else if (['draft','review','phone'].indexOf(data.mode)>=0) {
      if (!homeText(data.text,900)) return null;
      packet.ResponseText=data.text;
      if (data.mode==='review') {
        if (!homeId(data.review_id)||!Number.isInteger(data.expires_at)||data.expires_at<=now()||data.expires_at-now()>120) return null;
        packet.QuestionReview=data.review_id; packet.QuestionExpires=data.expires_at;
      }
    } else return null;
    questionDraft={id:data.draft_id,revision:data.revision,review:packet.QuestionReview,expires:packet.QuestionExpires};
    return packet;
  }
  function handleQuestion(p) {
    var kind=p.RequestType;
    if (p.QuestionReviewVersion!==1 || !questionReady) return send({RequestId:p.RequestId,StatusText:'Update the companion and review this question on your phone.',Complete:1});
    if (['question-open','question-home','question-systems','question-review','question-send','question-cancel','question-phone'].indexOf(kind)<0) return;
    if (questionSeen.indexOf(p.RequestId)>=0) return;
    var body={kind:kind,request_id:p.RequestId};
    if (kind==='question-open') {
      // Empty transcript is only a scoped phone handoff (for watches without a microphone).
      if (typeof p.Prompt!=='string'||utf8Bytes(p.Prompt)>400||p.Prompt.indexOf('\0')>=0||['none','capture','answer'].indexOf(p.QuestionContextKind)<0) return;
      body.prompt=p.Prompt; body.context_kind=p.QuestionContextKind;
      if (body.context_kind!=='none') { if (!homeId(p.QuestionContextId)) return; body.context_id=p.QuestionContextId; }
      if (p.QuestionDraft!==undefined) {
        if (!questionDraft || p.QuestionDraft!==questionDraft.id || p.QuestionRevision!==questionDraft.revision) return;
        body.draft_id=questionDraft.id; body.revision=questionDraft.revision;
      }
      questionDraft=null;
    } else {
      if (!questionDraft||p.QuestionDraft!==questionDraft.id||p.QuestionRevision!==questionDraft.revision) return;
      body.draft_id=questionDraft.id; body.revision=questionDraft.revision;
      if (kind==='question-home') {
        if (['none','read','actions'].indexOf(p.QuestionHomeMode)<0) return;
        body.mode=p.QuestionHomeMode;
        if (p.QuestionSystem!==undefined) { if (!homeId(p.QuestionSystem)||(p.QuestionSelected!==0&&p.QuestionSelected!==1)) return; body.connection_id=p.QuestionSystem; body.selected=!!p.QuestionSelected; }
      }
      if (kind==='question-systems') { if (!Number.isInteger(p.QuestionPage)||p.QuestionPage<0||p.QuestionPage>=1000) return; body.page=p.QuestionPage; }
      if (kind==='question-send') {
        if (!questionDraft.review||p.QuestionReview!==questionDraft.review||questionDraft.expires<=now()||questionDraft.expires-now()>120) return;
        body.review_id=questionDraft.review; questionDraft.review=null; // Claim before native dispatch.
      }
    }
    if (active && active.question) cancel(true);
    var a=attach(p.RequestId); a.question=body; a.questionSent=kind==='question-send';
    questionSeen.push(p.RequestId); if(questionSeen.length>64) questionSeen.shift();
    if (kind==='question-home'||kind==='question-systems') questionDraft.review=null;
    a.abort=native('POST','question',body,function(err,data) {
      if(!valid(a)) return;
      if(err) return error(a,err);
      if(kind==='question-cancel') { questionDraft=null; a.terminal=true; return; }
      if(kind==='question-send') { if(!data||data.mode!=='working') return error(a,'Question was not started. Review it on your phone.'); questionDraft=null; return poll(a); }
      a.questionPacket=questionPacket(a,data);
      if(!a.questionPacket) return error(a,'Question cannot be reviewed safely. Continue on your phone.');
      a.text=a.questionPacket.ResponseText||a.questionPacket.QuestionItems||'Question'; a.terminal=true; a.deliveries=0; deliver(a);
    });
  }
  return {
    sync:sync,
    cancel:cancel,
    configuration:function (command) {
      if (command && command.kind === 'refresh') return sync();
      if (command && command.kind==='question-review' && requestId(command.request_id)) {
        if (!questionReady || reviewedIds.indexOf(command.request_id)>=0) return;
        reviewedIds.push(command.request_id); if(reviewedIds.length>64) reviewedIds.shift();
        var offered=attach(command.request_id); offered.question={kind:'question-open',request_id:command.request_id};
        offered.questionPacket=questionPacket(offered,Object.assign({},command,{mode:'review'}));
        if(!offered.questionPacket) return error(offered,'Open the exact draft on your phone for review.');
        offered.questionPacket.Command='question-offer'; offered.text=offered.questionPacket.ResponseText; offered.terminal=true; offered.deliveries=0; deliver(offered);
        return;
      }
      if (command && command.kind === 'cancel' && requestId(command.request_id)) {
        if (active && active.id === command.request_id) {
          cancel(true);
          send({RequestId:command.request_id, Command:'cancel'});
        }
        return;
      }
      if (command && command.kind === 'review' && requestId(command.request_id)) {
        return send({RequestId:command.request_id,StatusText:'Review the complete draft on your phone. Update both apps for wrist review.',Complete:1});
      }
      if (!command || ['survey','capture','record','ask'].indexOf(command.kind) < 0 || !requestId(command.request_id)) return;
      var already = active && active.id === command.request_id;
      var a = attach(command.request_id);
      if (command.kind === 'record') a.recording = true;
      send({RequestId:a.id, Command:command.kind, Enabled:JSON.stringify(command.enabled || []),
        ConfirmTranscript:command.confirmTranscript ? 1 : 0});
      if (!already && command.kind !== 'record') poll(a);
    },
    settings:function () { native('POST', 'settings', {}); },
    handle:function (p) {
      if (!p) return;
      if (p.RequestType === 'ready') { homeVersion=p.HomeVersion===1?1:0; questionVersion=p.QuestionReviewVersion===1?1:0; return sync(); }
      if (p.RequestType === 'clear') { cancel(); return native('POST', 'clear', {}, function () { sync(); }); }
      if (p.RequestType === 'settings') return native('POST', 'settings', {});
      if (!requestId(p.RequestId)) return;
      if (typeof p.RequestType==='string' && p.RequestType.indexOf('question-')===0) return handleQuestion(p);
      if (typeof p.RequestType==='string' && p.RequestType.indexOf('home-')===0) return handleHome(p);
      if (p.RequestType === 'continue-phone') {
        var reply = active;
        if (!reply || reply.id !== p.RequestId || !reply.text || reply.history || !reply.terminal) {
          return send({RequestId:p.RequestId, Command:'phone-handoff', StatusText:'Reply unavailable. Find it in phone History.'});
        }
        if (reply.handoffPending) return;
        reply.handoffPending = true;
        native('POST', 'continue-phone', {request_id:reply.id}, function (err) {
          if (!valid(reply)) return;
          reply.handoffPending = false;
          send({RequestId:reply.id, Command:'phone-handoff', StatusText:err ? 'Could not prepare reply. Retry or open phone History.' : 'Open Signal Station on phone, then tap Open full reply.'});
        });
        return;
      }
      if (p.RequestType === 'cancel') { if (active && active.id === p.RequestId) cancel(); return; }
      if (p.RequestType === 'confirm-wake') {
        return send({RequestId:p.RequestId,StatusText:'Review the complete draft on your phone.',Complete:1});
      }
      if (p.TextAck !== undefined) {
        var a = active;
        if (!a || a.id !== p.RequestId || !a.text || a.delivered || a.committing) return;
        if (a.timer) clear(a.timer);
        if (a.history || a.home || (a.question && !a.questionSent)) { a.delivered = true; return; }
        return acknowledge(a, 1);
      }
      if (p.Snapshot !== undefined || p.RequestType === 'watch-data') {
        if (!active || active.id !== p.RequestId || active.terminal || active.reviewing) return;
        var observations;
        try { observations = p.Snapshot ? JSON.parse(p.Snapshot) : []; } catch (_) { return error(active, 'Invalid watch readings.'); }
        if (!Array.isArray(observations) || observations.length > 12) return error(active, 'Invalid watch readings.');
        if (active.watchQueue.length >= 24) return error(active, 'Too many watch packets.');
        active.watchQueue.push({request_id:p.RequestId, observations:observations, complete:!!p.Complete});
        return drainWatchData(active);
      }
      if (p.RequestType === 'history') {
        if (active && active.id === p.RequestId) return;
        var history = attach(p.RequestId);
        history.history = true;
        history.abort = native('GET', 'history?request_id=' + history.id, null, function (err, data) {
          if (!valid(history)) return;
          if (err) return error(history, err);
          if (!data || typeof data.text !== 'string' || !data.text.trim() || utf8Bytes(data.text) > MAX_TEXT_BYTES) {
            return error(history, 'Invalid history size. Check the phone.');
          }
          history.text = data.text; history.terminal = true; history.deliveries = 0;
          deliver(history);
        });
        return;
      }
      if (['ask','survey','capture','record'].indexOf(p.RequestType) < 0) return;
      if (p.RequestType==='ask') return send({RequestId:p.RequestId,StatusText:'This watch needs a companion update. Review questions on your phone.',Complete:1});
      var recordTransition = active && active.id === p.RequestId && active.recording && p.RequestType === 'ask';
      if (active && active.id === p.RequestId && !recordTransition) return; // A transport retry must not bill twice.
      if (p.RequestType === 'ask' && (typeof p.Prompt !== 'string' || !p.Prompt.trim() || utf8Bytes(p.Prompt) > 400)) {
        return send({RequestId:p.RequestId, StatusText:'Question is empty or too long.', Complete:1});
      }
      var a = attach(p.RequestId);
      a.recording = false;
      a.abort = native('POST', 'start', {kind:p.RequestType, request_id:p.RequestId, prompt:p.Prompt || undefined}, function (err) {
        if (!valid(a)) return;
        if (err) return error(a, err);
        poll(a);
      });
    }
  };
}
module.exports = {createClient:createClient, BASE:BASE, utf8Bytes:utf8Bytes, MAX_TEXT_BYTES:MAX_TEXT_BYTES};
