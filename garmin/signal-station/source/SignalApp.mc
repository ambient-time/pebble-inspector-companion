import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Time;
import Toybox.WatchUi;

const SIGNAL_CYAN = 0xA5EEF5;
const SIGNAL_AMBER = 0xFFC044;

(:glance)
class SignalApp extends Application.AppBase {
    // Null in the glance process, which never opens the phone link.
    var link = null;

    function initialize() {
        AppBase.initialize();
    }

    function onStop(state as Dictionary?) as Void {
        if (link != null) {
            link.stop();
        }
    }

    function getInitialView() {
        link = new SignalLink();
        link.start();
        return [new MainMenu(), new MainMenuDelegate()];
    }

    function getGlanceView() {
        return [new SignalGlanceView()];
    }
}

(:glance)
class SignalGlanceView extends WatchUi.GlanceView {
    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var h = dc.getHeight();
        dc.setColor(SIGNAL_CYAN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(0, h / 4, Graphics.FONT_GLANCE, "Signal Station", Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(0, (h * 3) / 4, Graphics.FONT_GLANCE, glanceLine(), Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    function glanceLine() as String {
        var line = Application.Storage.getValue("glance");
        var saved = Application.Storage.getValue("glanceAt");
        if (!(line instanceof String) || !(saved instanceof Number)) {
            return "Home favorites";
        }
        return line + " · " + ageText(Time.now().value() - saved);
    }

    function ageText(seconds as Number) as String {
        if (seconds < 60) {
            return "now";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        }
        if (seconds < 86400) {
            return (seconds / 3600) + "h ago";
        }
        return (seconds / 86400) + "d ago";
    }
}

function signalLink() as SignalLink {
    return (Application.getApp() as SignalApp).link as SignalLink;
}
