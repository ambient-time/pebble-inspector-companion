import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;
import Toybox.Timer;

// Local statuses for requests that never reached the phone's handler.
const LINK_TIMEOUT = -1;
const LINK_UNREACHABLE = -2;

class SignalTransmit extends Communications.ConnectionListener {
    private var _link as SignalLink;
    private var _id as Number;

    function initialize(link as SignalLink, id as Number) {
        ConnectionListener.initialize();
        _link = link;
        _id = id;
    }

    function onComplete() as Void {
    }

    function onError() as Void {
        _link.transmitFailed(_id);
    }
}

// Request/response over the Garmin Connect mailbox. Each app run has its own
// session number; replies for another session or an unknown request are dropped.
class SignalLink {
    const TIMEOUT_MS = 15000;

    var session as Number;
    var ready as Boolean = false;
    private var _pending as Dictionary = {};
    private var _deadlines as Dictionary = {};
    private var _queue as Array = [];
    private var _checking as Boolean = false;
    private var _timer as Timer.Timer?;

    function initialize() {
        Math.srand(System.getTimer());
        session = Math.rand() % 2000000000 + 1;
    }

    function start() as Void {
        Communications.registerForPhoneAppMessages(method(:onPhone));
        send({"t" => "hello"}, 0);
        _timer = new Timer.Timer();
        _timer.start(method(:tick), 1000, true);
    }

    function stop() as Void {
        if (_timer != null) {
            _timer.stop();
            _timer = null;
        }
        send({"t" => "bye"}, 0);
    }

    // callback(status as Number, data as Dictionary or Null). Returns the message id.
    // A cancel names the request it cancels, so an existing request_id is kept.
    function call(route as String, body as Dictionary, callback as Method) as Number {
        var id = nextId();
        if (!body.hasKey("request_id")) {
            body["request_id"] = id;
        }
        if (ready || route.equals("capabilities")) {
            dispatch(id, route, body, callback);
        } else {
            _queue.add([id, route, body, callback]);
            if (!_checking) {
                check();
            }
        }
        return id;
    }

    function check() as Void {
        _checking = true;
        call("capabilities", {}, method(:onCapabilities));
    }

    // Drops a pending callback; nothing is resent.
    function cancel(id as Number?) as Void {
        if (id == null) {
            return;
        }
        _pending.remove(id);
        _deadlines.remove(id);
        for (var i = _queue.size() - 1; i >= 0; i--) {
            var item = _queue[i] as Array;
            if (item[0] == id) {
                _queue.remove(item);
            }
        }
    }

    function onCapabilities(status as Number, data) as Void {
        _checking = false;
        ready = status == 200;
        var waiting = _queue;
        _queue = [];
        for (var i = 0; i < waiting.size(); i++) {
            var item = waiting[i] as Array;
            if (ready) {
                dispatch(item[0], item[1], item[2], item[3]);
            } else {
                (item[3] as Method).invoke(status, null);
            }
        }
    }

    function onPhone(message as Communications.PhoneAppMessage) as Void {
        var data = message.data;
        if (!(data instanceof Dictionary) || data.get("s") != session) {
            return;
        }
        var kind = data.get("t");
        if ("res".equals(kind)) {
            var id = data.get("id");
            var callback = _pending.get(id);
            if (callback == null) {
                return;
            }
            _pending.remove(id);
            _deadlines.remove(id);
            var status = data.get("status");
            if (!(status instanceof Number)) {
                status = 500;
            }
            if (status == 403) {
                ready = false;
            }
            var payload = data.get("data");
            (callback as Method).invoke(status, payload instanceof Dictionary ? payload : null);
        } else if ("reset".equals(kind)) {
            ready = false;
        }
    }

    function transmitFailed(id as Number) as Void {
        var callback = _pending.get(id);
        if (callback != null) {
            _pending.remove(id);
            _deadlines.remove(id);
            (callback as Method).invoke(LINK_UNREACHABLE, null);
        }
    }

    function tick() as Void {
        var now = System.getTimer();
        var expired = [];
        var ids = _deadlines.keys();
        for (var i = 0; i < ids.size(); i++) {
            if (now - (_deadlines[ids[i]] as Number) >= 0) {
                expired.add(ids[i]);
            }
        }
        for (var i = 0; i < expired.size(); i++) {
            var callback = _pending.get(expired[i]);
            _pending.remove(expired[i]);
            _deadlines.remove(expired[i]);
            if (callback != null) {
                (callback as Method).invoke(LINK_TIMEOUT, null);
            }
        }
    }

    private function dispatch(id as Number, route as String, body as Dictionary, callback as Method) as Void {
        _pending[id] = callback;
        _deadlines[id] = System.getTimer() + TIMEOUT_MS;
        send({"t" => "req", "id" => id, "route" => route, "body" => body}, id);
    }

    private function send(message as Dictionary, id as Number) as Void {
        message["v"] = 1;
        message["s"] = session;
        Communications.transmit(message, null, new SignalTransmit(self, id));
    }

    private function nextId() as Number {
        var id = Application.Storage.getValue("requestId");
        if (!(id instanceof Number) || id < 1 || id >= 2000000000) {
            id = 0;
        }
        id = id + 1;
        Application.Storage.setValue("requestId", id);
        return id;
    }
}

function linkError(status as Number) as String {
    if (status == LINK_TIMEOUT) {
        return "No reply from the phone. Open Signal Station on your phone and try again.";
    }
    if (status == LINK_UNREACHABLE) {
        return "Phone disconnected. Check Garmin Connect on your phone.";
    }
    if (status == 403) {
        return "The phone did not accept this watch. Check that it is chosen in Signal Station settings, then try again.";
    }
    if (status == 409) {
        return "Signal Station is busy on the phone. Try again in a moment.";
    }
    if (status == 503) {
        return "Signal Station is still starting on your phone. Try again.";
    }
    return "Open or update Signal Station on your phone to use Home favorites.";
}
