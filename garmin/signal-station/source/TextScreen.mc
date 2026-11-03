import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// Title, scrolling body and a one-line control hint, kept inside the round inset.
class TextScreen extends WatchUi.View {
    var title as String;
    var body as String;
    var hint as String;
    private var _lines as Array<String>? = null;
    private var _top as Number = 0;
    private var _visible as Number = 1;

    function initialize(title as String, body as String, hint as String) {
        View.initialize();
        self.title = title;
        self.body = body;
        self.hint = hint;
    }

    function set(title as String, body as String, hint as String) as Void {
        self.title = title;
        self.body = body;
        self.hint = hint;
        _lines = null;
        _top = 0;
        WatchUi.requestUpdate();
    }

    function scroll(step as Number) as Boolean {
        if (_lines == null) {
            return false;
        }
        var last = _lines.size() - _visible;
        var next = _top + step * _visible;
        if (next > last) {
            next = last;
        }
        if (next < 0) {
            next = 0;
        }
        if (next == _top) {
            return false;
        }
        _top = next;
        WatchUi.requestUpdate();
        return true;
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var font = Graphics.FONT_SMALL;
        var lineHeight = dc.getFontHeight(font);
        var areaTop = h * 23 / 100;
        var areaBottom = h * 79 / 100;
        var width = w * 76 / 100;
        var left = (w - width) / 2;

        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        if (_lines == null) {
            _lines = wrapText(dc, body, font, width);
        }
        _visible = (areaBottom - areaTop) / lineHeight;
        if (_visible < 1) {
            _visible = 1;
        }

        dc.setColor(SIGNAL_CYAN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 12 / 100, Graphics.FONT_TINY, title, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        var lines = _lines as Array<String>;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        for (var i = 0; i < _visible && _top + i < lines.size(); i++) {
            dc.drawText(left, areaTop + i * lineHeight, font, lines[_top + i], Graphics.TEXT_JUSTIFY_LEFT);
        }

        dc.setColor(SIGNAL_AMBER, Graphics.COLOR_TRANSPARENT);
        if (_top > 0) {
            dc.fillPolygon([[w / 2 - 7, areaTop - 4], [w / 2 + 7, areaTop - 4], [w / 2, areaTop - 11]]);
        }
        if (_top + _visible < lines.size()) {
            dc.fillPolygon([[w / 2 - 7, areaBottom + 2], [w / 2 + 7, areaBottom + 2], [w / 2, areaBottom + 9]]);
        }

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 87 / 100, Graphics.FONT_XTINY, hint, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}

class TextDelegate extends WatchUi.BehaviorDelegate {
    private var _screen as TextScreen;
    private var _select as Method?;
    private var _back as Method?;

    function initialize(screen as TextScreen, select as Method?, back as Method?) {
        BehaviorDelegate.initialize();
        _screen = screen;
        _select = select;
        _back = back;
    }

    function onNextPage() as Boolean {
        _screen.scroll(1);
        return true;
    }

    function onPreviousPage() as Boolean {
        _screen.scroll(-1);
        return true;
    }

    function onSelect() as Boolean {
        if (_select != null) {
            (_select as Method).invoke();
        }
        return true;
    }

    function onBack() as Boolean {
        if (_back != null) {
            (_back as Method).invoke();
            return true;
        }
        return false;
    }
}

function splitText(text as String, separator as String) as Array<String> {
    var parts = [] as Array<String>;
    var rest = text;
    var index = rest.find(separator);
    while (index != null) {
        parts.add(rest.substring(0, index) as String);
        rest = rest.substring(index + separator.length(), rest.length()) as String;
        index = rest.find(separator);
    }
    parts.add(rest);
    return parts;
}

// Word wrap by measured width; a word wider than the line is broken by character.
function wrapText(dc as Graphics.Dc, text as String, font as Graphics.FontType, width as Number) as Array<String> {
    var lines = [] as Array<String>;
    var paragraphs = splitText(text, "\n");
    for (var p = 0; p < paragraphs.size(); p++) {
        var words = splitText(paragraphs[p], " ");
        var line = "";
        for (var i = 0; i < words.size(); i++) {
            var word = words[i];
            var candidate = line.length() == 0 ? word : line + " " + word;
            if (dc.getTextWidthInPixels(candidate, font) <= width) {
                line = candidate;
                continue;
            }
            if (line.length() > 0) {
                lines.add(line);
            }
            line = "";
            var chars = word.toCharArray();
            for (var c = 0; c < chars.size(); c++) {
                var next = line + chars[c];
                if (line.length() > 0 && dc.getTextWidthInPixels(next, font) > width) {
                    lines.add(line);
                    line = chars[c].toString();
                } else {
                    line = next;
                }
            }
        }
        lines.add(line);
    }
    return lines;
}
