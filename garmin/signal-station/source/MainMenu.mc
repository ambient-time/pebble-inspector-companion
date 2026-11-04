import Toybox.Lang;
import Toybox.WatchUi;

class MainMenu extends WatchUi.Menu2 {
    function initialize() {
        Menu2.initialize({:title => "Signal Station"});
        addItem(new WatchUi.MenuItem("Home favorites", "Read and review", :home, null));
        addItem(new WatchUi.MenuItem("Recent replies", "Saved on your phone", :history, null));
        addItem(new WatchUi.MenuItem("Home on phone", "Open the Home view", :phone, null));
        addItem(new WatchUi.MenuItem("Check connection", null, :check, null));
    }
}

class MainMenuDelegate extends WatchUi.Menu2InputDelegate {
    function initialize() {
        Menu2InputDelegate.initialize();
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (id == :home) {
            new HomeFlow().start();
        } else if (id == :history) {
            new NoticeFlow("RECENT HISTORY", "history", {}).start();
        } else if (id == :phone) {
            new NoticeFlow("HOME", "home", {"kind" => "home-phone", "favorite_id" => ""}).start();
        } else if (id == :check) {
            new NoticeFlow("CONNECTION", "capabilities", {}).start();
        }
    }
}

// One request, one text reply.
class NoticeFlow {
    private var _title as String;
    private var _route as String;
    private var _body as Dictionary;
    private var _screen as TextScreen;
    private var _active as Number? = null;

    function initialize(title as String, route as String, body as Dictionary) {
        _title = title;
        _route = route;
        _body = body;
        _screen = new TextScreen(title, "Contacting your phone...", "Back: cancel");
    }

    function start() as Void {
        WatchUi.pushView(_screen, new TextDelegate(_screen, null, method(:close)), WatchUi.SLIDE_LEFT);
        _active = signalLink().call(_route, _body, method(:onReply));
    }

    function onReply(status as Number, data as Dictionary?) as Void {
        _active = null;
        var text;
        if (status != 200) {
            text = linkError(status);
        } else if (_route.equals("capabilities")) {
            text = "Connected to Signal Station.";
        } else {
            text = replyText(data, "Nothing saved yet.");
        }
        _screen.set(_title, text, "Up/Down: read");
    }

    function close() as Void {
        signalLink().cancel(_active);
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
    }
}

function replyText(data as Dictionary?, fallback as String) as String {
    if (data != null) {
        var text = data.get("text");
        if (text instanceof String && text.length() > 0) {
            return text;
        }
    }
    return fallback;
}

function replyValue(data as Dictionary?, key as String) as String {
    if (data != null) {
        var value = data.get(key);
        if (value instanceof String) {
            return value;
        }
        if (value instanceof Number) {
            return value.toString();
        }
    }
    return "";
}
