import Toybox.Application;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.WatchUi;

// Favorites list -> reading -> exact review -> system confirmation -> result.
// The phone owns every Home decision; a sent confirmation is never resent.
class HomeFlow {
    // The phone allows two minutes; stop accepting the review a little earlier.
    const REVIEW_MS = 110000;

    private var _screen as TextScreen;
    private var _state as Symbol = :loading;
    private var _loading as Symbol = :list;
    private var _active as Number? = null;
    private var _onMenu as Boolean = false;
    private var _page as Number = 0;
    private var _pages as Number = 1;
    private var _items as Array = [];
    private var _favorite as String = "";
    private var _label as String = "";
    private var _action as String = "";
    private var _intent as String = "";
    private var _reviewId as Number = 0;
    private var _reviewUntil as Number = 0;

    function initialize() {
        _screen = new TextScreen("HOME FAVORITES", "Contacting Home on the phone...", "Back: cancel");
    }

    function start() as Void {
        WatchUi.pushView(_screen, screenDelegate(), WatchUi.SLIDE_LEFT);
        loadPage(0);
    }

    function loadPage(page as Number) as Void {
        _page = page;
        wait(:list, "HOME FAVORITES", "Contacting Home on the phone...");
        _active = signalLink().call("home", {"kind" => "home-list", "page" => page}, method(:onList));
    }

    function onList(status as Number, data as Dictionary?) as Void {
        _active = null;
        if (status != 200 || data == null) {
            failed("HOME FAVORITES", status);
            return;
        }
        if (!replyValue(data, "mode").equals("list")) {
            _favorite = "";
            show(:handoff, "HOME FAVORITES", replyText(data, "Continue on the phone."), "Select: phone | Back");
            return;
        }
        var items = data.get("items");
        var pages = data.get("pages");
        _items = items instanceof Array ? items : [];
        _pages = pages instanceof Number && pages > 0 ? pages : 1;
        if (_items.size() == 0) {
            _favorite = "";
            show(:empty, "HOME FAVORITES", "Choose Home favorites on your phone.", "Select: phone | Back");
            return;
        }
        saveGlance();
        showMenu();
    }

    function open(index as Number) as Void {
        var item = _items[index];
        if (!(item instanceof Dictionary)) {
            return;
        }
        _favorite = replyValue(item, "id");
        _label = replyValue(item, "label");
        _action = "";
        _intent = "";
        wait(:detail, _label, "Reading from Home...");
        _active = signalLink().call("home", {"kind" => "home-open", "favorite_id" => _favorite}, method(:onDetail));
    }

    function onDetail(status as Number, data as Dictionary?) as Void {
        _active = null;
        if (status != 200 || data == null) {
            failed(_label, status);
            return;
        }
        var mode = replyValue(data, "mode");
        if (mode.equals("detail")) {
            _action = replyValue(data, "action_id");
            show(:detail, _label, replyText(data, "No reading."), _action.length() > 0 ? "Select: review | Back" : "Back: favorites");
        } else if (mode.equals("handoff")) {
            show(:handoff, _label, replyText(data, "Continue on the phone."), "Select: phone | Back");
        } else {
            show(:result, _label, replyText(data, "Unavailable."), "Back: favorites");
        }
    }

    function review() as Void {
        wait(:review, "REVIEW ACTION", "Preparing the exact action...");
        _active = signalLink().call("home", {"kind" => "home-review", "favorite_id" => _favorite, "action_id" => _action}, method(:onReview));
        _reviewId = _active as Number;
    }

    function onReview(status as Number, data as Dictionary?) as Void {
        _active = null;
        if (status != 200 || data == null) {
            failed("REVIEW ACTION", status);
            return;
        }
        var mode = replyValue(data, "mode");
        _intent = replyValue(data, "intent_id");
        if (mode.equals("review") && _intent.length() > 0 && replyValue(data, "action_id").equals(_action)) {
            _reviewUntil = System.getTimer() + REVIEW_MS;
            show(:review, "REVIEW ACTION", replyText(data, ""), "Select: confirm | Back: cancel");
        } else if (mode.equals("handoff")) {
            show(:handoff, "REVIEW ACTION", replyText(data, "Review the complete action on the phone."), "Select: phone | Back");
        } else {
            cancelIntent();
            show(:result, "REVIEW ACTION", replyText(data, "Home message could not be reviewed safely. Continue on the phone."), "Back: favorites");
        }
    }

    // Called by the system confirmation after the wearer chooses Yes.
    function confirm() as Void {
        if (_state != :review) {
            return;
        }
        if (System.getTimer() - _reviewUntil >= 0) {
            expired();
            return;
        }
        _state = :confirming;
        _screen.set("HOME", "Sending to Home...", "Back: stop");
        _active = signalLink().call("home", {"kind" => "home-confirm", "favorite_id" => _favorite, "intent_id" => _intent, "action_id" => _action}, method(:onResult));
    }

    function onResult(status as Number, data as Dictionary?) as Void {
        _active = null;
        _intent = "";
        if (status == 200 && data != null) {
            show(:result, "HOME", replyText(data, "Done."), "Back: favorites");
        } else if (status == LINK_TIMEOUT || status == LINK_UNREACHABLE) {
            show(:result, "HOME", "No reply from the phone. The action may or may not have run. Check Home activity on the phone.", "Back: favorites");
        } else {
            show(:result, "HOME", linkError(status), "Back: favorites");
        }
    }

    function onStopped(status as Number, data as Dictionary?) as Void {
        if (_state == :stopping) {
            show(:result, "HOME", status == 200 ? replyText(data, "Stopped.") : "Check Home activity on the phone.", "Back: favorites");
        }
    }

    function onPhone(status as Number, data as Dictionary?) as Void {
        _active = null;
        show(:result, "HOME", status == 200 ? replyText(data, "Home is ready to review on the phone.") : linkError(status), "Back");
    }

    function ignore(status as Number, data as Dictionary?) as Void {
    }

    function select() as Void {
        if (_state == :detail && _action.length() > 0) {
            review();
        } else if (_state == :review) {
            if (System.getTimer() - _reviewUntil >= 0) {
                expired();
            } else {
                WatchUi.pushView(new WatchUi.Confirmation("Run this action now?"), new ConfirmActionDelegate(self), WatchUi.SLIDE_UP);
            }
        } else if (_state == :handoff || _state == :empty) {
            wait(:phone, "HOME", "Contacting your phone...");
            _active = signalLink().call("home", {"kind" => "home-phone", "favorite_id" => _favorite}, method(:onPhone));
        }
    }

    function back() as Void {
        if (_state == :loading) {
            signalLink().cancel(_active);
            _active = null;
            if (_loading == :review) {
                // A lost reply can leave a prepared intent; cancel it by request.
                signalLink().call("home", {"kind" => "home-cancel", "favorite_id" => _favorite, "action_id" => _action, "request_id" => _reviewId}, method(:ignore));
            }
            listOrClose();
        } else if (_state == :review) {
            cancelIntent();
            listOrClose();
        } else if (_state == :confirming) {
            _state = :stopping;
            _screen.set("HOME", "Stopping further dispatch...", "Back: favorites");
            signalLink().call("home", {"kind" => "home-cancel", "favorite_id" => _favorite, "intent_id" => _intent, "action_id" => _action, "request_id" => _reviewId}, method(:onStopped));
        } else if (_state == :list || _state == :empty) {
            close();
        } else {
            listOrClose();
        }
    }

    function close() as Void {
        signalLink().cancel(_active);
        _active = null;
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
    }

    private function expired() as Void {
        cancelIntent();
        show(:result, "REVIEW ACTION", "Review expired. Open the favorite again for a new review.", "Back: favorites");
    }

    private function cancelIntent() as Void {
        if (_intent.length() > 0) {
            signalLink().call("home", {"kind" => "home-cancel", "favorite_id" => _favorite, "intent_id" => _intent, "action_id" => _action, "request_id" => _reviewId}, method(:ignore));
            _intent = "";
        }
    }

    private function listOrClose() as Void {
        if (_items.size() > 0) {
            showMenu();
        } else {
            close();
        }
    }

    private function failed(title as String, status as Number) as Void {
        show(:result, title, linkError(status), _items.size() > 0 ? "Back: favorites" : "Back");
    }

    private function wait(kind as Symbol, title as String, text as String) as Void {
        _loading = kind;
        show(:loading, title, text, "Back: cancel");
    }

    private function show(state as Symbol, title as String, text as String, hint as String) as Void {
        _state = state;
        _screen.set(title, text, hint);
        if (_onMenu) {
            _onMenu = false;
            WatchUi.switchToView(_screen, screenDelegate(), WatchUi.SLIDE_IMMEDIATE);
        }
    }

    private function showMenu() as Void {
        var title = _pages > 1 ? "Favorites " + (_page + 1) + "/" + _pages : "Home favorites";
        var menu = new WatchUi.Menu2({:title => title});
        for (var i = 0; i < _items.size(); i++) {
            var item = _items[i];
            if (item instanceof Dictionary) {
                menu.addItem(new WatchUi.MenuItem(replyValue(item, "label"), null, i, null));
            }
        }
        if (_page > 0) {
            menu.addItem(new WatchUi.MenuItem("Previous page", null, :previous, null));
        }
        if (_page + 1 < _pages) {
            menu.addItem(new WatchUi.MenuItem("Next page", null, :next, null));
        }
        _state = :list;
        _onMenu = true;
        WatchUi.switchToView(menu, new FavoritesDelegate(self), WatchUi.SLIDE_IMMEDIATE);
    }

    function pageAfter(step as Number) as Void {
        loadPage(_page + step);
    }

    private function screenDelegate() as TextDelegate {
        return new TextDelegate(_screen, method(:select), method(:back));
    }

    private function saveGlance() as Void {
        var line = "";
        for (var i = 0; i < _items.size() && line.length() < 32; i++) {
            var label = replyValue(_items[i] as Dictionary?, "label");
            line = line.length() == 0 ? label : line + ", " + label;
        }
        Application.Storage.setValue("glance", line);
        Application.Storage.setValue("glanceAt", Time.now().value());
    }
}

class FavoritesDelegate extends WatchUi.Menu2InputDelegate {
    private var _flow as HomeFlow;

    function initialize(flow as HomeFlow) {
        Menu2InputDelegate.initialize();
        _flow = flow;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (id == :next) {
            _flow.pageAfter(1);
        } else if (id == :previous) {
            _flow.pageAfter(-1);
        } else if (id instanceof Number) {
            _flow.open(id);
        }
    }

    function onBack() as Void {
        _flow.close();
    }
}

class ConfirmActionDelegate extends WatchUi.ConfirmationDelegate {
    private var _flow as HomeFlow;

    function initialize(flow as HomeFlow) {
        ConfirmationDelegate.initialize();
        _flow = flow;
    }

    function onResponse(response as WatchUi.Confirm) as Boolean {
        if (response == WatchUi.CONFIRM_YES) {
            _flow.confirm();
        }
        return true;
    }
}
