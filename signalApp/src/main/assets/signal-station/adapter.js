(function () {
  'use strict';
  var callbacks = {}, next = 0, queue = [], sending = false;
  function callback(fn) { var id = ++next; callbacks[id] = fn; return id; }
  window.nativeReply = function (id, ok, data) {
    var fn = callbacks[id]; delete callbacks[id]; if (fn) fn(ok, data);
  };
  function flush() {
    if (sending || !queue.length) return;
    var job = queue.shift();
    if (!job.valid()) return flush();
    sending = true;
    SignalHost.send(callback(function (ok) {
      sending = false; (ok ? job.done : job.failed)(); flush();
    }), JSON.stringify(job.packet));
  }
  var client = module.exports.createClient({
    send: function (packet, done, failed, valid) {
      queue.push({packet:packet,done:done,failed:failed,valid:valid}); flush();
    },
    request: function (method, url, body, done) {
      var id = callback(function (ok, data) { done(ok ? null : 'Open Signal Station and check the watch connection.', data); });
      SignalHost.request(id, method, url, body ? JSON.stringify(body) : '{}');
      return function () { delete callbacks[id]; };
    }
  });
  window.receiveWatch = function (data) { client.handle(data); };
  window.configureWatch = function (data) { client.configuration(data); };
  window.startProtocol = function () { client.sync(); };
}());
