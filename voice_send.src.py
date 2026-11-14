__id__ = "voice_send"
__name__ = "Voice Send"
__description__ = "Say \"send\" while dictating with Gboard voice typing and the message goes out, exactly like tapping the send button. Say \"paste\" and the clipboard text goes in. Say \"screenshot\" or \"picture\" and your newest screenshot or camera photo is attached, say it again for the one before. Works on the first try, right after a sentence or spoken punctuation. The Enter key, Send by Enter and keyboard suggestions stay as they are. Made by [firestarter1996](https://github.com/firestarter1996)."
__author__ = "firestarter1996"
__version__ = "1.4.1"
__min_version__ = "12.5.1"

# Two routes to the same tap of the send button.
#
# 1. Gboard's own command. Gboard runs its voice "send" command (SendPerformer) only in an app on
#    its built in list or in a field that says it takes a send action (imeOptions action SEND, or
#    the private IME option "handlesSendAction"). The plugin adds only "handlesSendAction" to the
#    chat message field; imeOptions stay untouched, so the Enter key, Send by Enter, autocorrect and
#    suggestions are unchanged. When Gboard decides "send" is a command it calls
#    performEditorAction(IME_ACTION_SEND), which a hook on TextView.onEditorAction answers.
#
# 2. The typed word. Gboard's modeless command detector (SmartEdit, a MobileBERT intent classifier
#    with a 0.95 threshold that also reads the text before the cursor and spoken punctuation) often
#    does not call "send" a command: when it is said straight after the sentence with no pause, or
#    right after a spoken punctuation command such as "close parenthesis", it is finalized as the
#    last word of the dictated text and typed. So the message field's own InputConnection is watched
#    for the moment Gboard finalizes a chunk (commitText, or finishComposingText over a composing
#    region) whose LAST word is "send" (any case, punctuation or spaces after it allowed). If voice
#    typing is on (any app has the microphone open, or closed it less than 3 seconds ago, tracked by
#    an event driven AudioRecordingCallback) the word is dropped and the send button is pressed.
#    Whatever word comes before it. Not a command: typed or swiped text (no microphone), "send" in
#    the middle of a chunk ("send it to Tim") and longer words ("resend").
#
# 3. Spoken "paste" (Gboard may write "past"). The same watch, the same voice typing window: when
#    the last word of a finished chunk is "paste", the word is replaced by the clipboard text,
#    without sending. "paste send" pastes, then sends. An empty clipboard leaves the word as text.
#    Stays text after "copy and", "cut and", "copy", "the", "half", "went" and similar words.
#    The clipboard is read only at that moment and never logged.
#
# 4. Spoken "screenshot" (also "screen shot", "screenshots"). The same watch, the same voice typing
#    window: when the last word of a finished chunk is "screenshot", the word is dropped and the
#    newest screenshot on the phone is put in a small tray above the message box, not sent yet.
#    Every further "screenshot" adds the next newest one (never the same file twice, ten at most).
#    Sending the message (by voice, the send button or Enter) sends the tray as one album through
#    the app's own ChatActivity.didSelectSearchPhotos, the text in the box as its caption.
#    "screenshot send" attaches, then sends. The tray empties when the message goes out, when its X
#    is tapped, when the box is emptied by hand and when the chat is left. Stays text after "a",
#    "the", "this", "my", "of", "take" and similar words, and while a message is being edited.
#    Stock XF has no place for a photo to wait in the message box: its attach sheet is a separate
#    window that takes the keyboard (and so the dictation) away, and it forgets its ticked photos
#    every time it opens. So the tray is the plugin's own (ShotTray, Java, in the dex below).
#    The lookup (one MediaStore query for the newest rows) and the thumbnail run on a background
#    thread. While the tray is empty nothing of it is registered.
#    A screenshot or photo taken a moment ago is still being written for a second or more (its row
#    is "pending" and hidden from a normal query), so 1.4.0 took the one before it. Since 1.4.1 the
#    same query includes rows still being written; when the newest one not in the tray is such a
#    row (added in the last minute), the background thread checks it every 0.1 s until it is
#    finished, then takes it (8 s at most, then the one before as before; stops when the chat is
#    left). Nothing pending: no extra query, no wait.
#
# 5. Spoken "picture". Exactly like "screenshot", for the newest photo of the camera roll
#    (DCIM/Camera, pictures only, no videos). Both kinds share one tray, so "screenshot", "picture",
#    "picture" makes one album in the order spoken. Its own switch.
#
# 6. A long press on the paperclip. Not a voice command, but the same tray: holding the paperclip
#    adds the newest screenshot, then the one before it about every 0.7 seconds until the finger
#    lifts, with a light tick each (unless the app's vibration is off). A short tap still opens the
#    attach menu; stock XF has nothing on a long press of that button. ClipHold (Java): the view's
#    own long press detection plus one posted repeat while held. Its own switch.
#
# Cost: one Python call per commitText and finishComposingText of the message field (once per word,
# never per letter: letters go through setComposingText, which is not hooked) and a string test on
# the end of the chunk; no binder call, nothing per frame, no polling. The microphone state comes
# from Android's recording callback, which fires only when a recording starts or stops.
# Logcat tag VoiceSend: errors, one line per spoken send the plugin catches, and a call trace when
# files/voice_send_debug exists at load.

import re
from java import jclass
from base_plugin import BasePlugin, MethodHook
from hook_utils import find_class, get_private_field
from client_utils import get_last_fragment
from ui.settings import Header, Switch

TOKEN = "handlesSendAction"
IME_ACTION_SEND = 4
GUARD_MS = 1500
VOICE_GRACE_MS = 3000
MICWATCH = "com.firestarter.voicesend.MicWatch"
SHOTTRAY = "com.firestarter.voicesend.ShotTray"
CLIPHOLD = "com.firestarter.voicesend.ClipHold"
CAPTION_MAX = 1024
_TRAIL = " \t\n.!?"


def _log(msg):
    try:
        jclass("android.util.Log").i("VoiceSend", str(msg))
    except Exception:
        pass


def _hint(s):
    """Cheap first test on the last few letters: could the chunk end in send, paste, past or screenshot?"""
    low = s[-8:].lower()
    return "end" in low or "ast" in low or "hot" in low or "ture" in low


def _without(opts):
    parts = [p for p in str(opts or "").replace(";", ",").split(",") if p and p.strip() != TOKEN]
    return ",".join(parts) if parts else None


# "paste" (Gboard may write it "past") stays text after these words: "copy and paste",
# "cut and paste", "copy paste", "the past", "half past", "went past" and the like.
_PASTE_TEXT_AFTER = {"the", "copy", "half", "quarter", "went", "go", "goes", "going",
                     "walked", "drove", "ran",  "way", "long", "far"}


# "screenshot" stays text after these words: "take a screenshot", "the screenshot", "send me a
# screenshot", "see screenshot", "this screenshot" and the like.
_SHOT_TEXT_AFTER = {"a", "an", "the", "this", "that", "these", "those", "my", "your", "his", "her",
                    "its", "our", "their", "another", "one", "two", "three", "few", "some", "any",
                    "no", "each", "every", "of", "in", "on", "with", "via", "by", "from", "to",
                    "for", "per", "see", "attached", "new", "old", "last", "latest", "recent",
                    "first", "second", "next", "previous", "same", "full", "quick", "and", "or",
                    "take", "takes", "took", "taking", "taken", "send", "sent", "sending", "sends",
                    "share", "shared", "grab", "grabbed", "that's", "it's", "big", "small", "long",
                    "whole", "blurry", "many", "several", "profile", "motion", "bigger", "good",
                    "nice", "great", "cute", "pretty", "beautiful", "perfect", "best", "family",
                    "group", "wedding", "clear", "clearer", "better", "pretty", "mental", "in",
                    "paint", "paints", "painted", "snap", "snapped", "shoot", "shot", "like", "you"}
# spoken word -> kind for ShotTray.add (0 screenshot, 1 camera picture)
_SHOT_WORDS = (("screenshots", 0), ("screenshot", 0), ("screen shots", 0), ("screen shot", 0), ("picture", 1))


def _shots(t, kinds_on=(True, True)):
    """Trailing spoken "screenshot" and "picture" words: (text before them, kinds in spoken order), or None."""
    kinds = []
    head = t
    while len(kinds) < 10:
        cur = head.rstrip(_TRAIL + ",")
        low = cur[-12:].lower()
        hit = None
        for w, k in _SHOT_WORDS:
            if kinds_on[k] and low.endswith(w):
                rest = cur[:-len(w)]
                if rest and (rest[-1].isalnum() or rest[-1] in "'\u2019_-"):
                    continue
                hit = rest
                kinds.insert(0, k)
                break
        if hit is None:
            break
        head = hit
    if not kinds:
        return None
    if _shot_is_text(head):
        return None
    return head, kinds


def _shot_is_text(head):
    """Do the words before "screenshot" make it an ordinary word ("take a", "the", "this")?"""
    words = head.lower().replace(",", " ").split()
    return bool(words) and words[-1] in _SHOT_TEXT_AFTER


def _cut(t, words):
    """If t ends with one of words as a whole word (any case), return (text before it, word)."""
    low = t[-6:].lower()
    for w in words:
        if low.endswith(w):
            head = t[:-len(w)]
            if head and (head[-1].isalnum() or head[-1] in "'\u2019_-"):
                return None  # part of a longer word: resend, toothpaste
            return head, w
    return None


def _tidy(head):
    keep = head.rstrip()
    if keep.endswith(","):
        keep = keep[:-1].rstrip()
    return keep


def _parse(s, shots_on=True, pics_on=True):
    """Spoken commands at the end of a finished chunk.

    Returns None, or (text without "send" and "screenshot", send, head before "paste" or None,
    the kinds of "screenshot" (0) and "picture" (1) said, in order)."""
    if not s:
        return None
    t = s.rstrip(_TRAIL)
    send = False
    c = _cut(t, ("send",))
    if c is not None:
        send = True
        t = _tidy(c[0])
    shots = []
    if shots_on or pics_on:
        c = _shots(t, (shots_on, pics_on))
        if c is not None:
            shots = c[1]
            t = _tidy(c[0])
    paste = None
    c = _cut(t.rstrip(_TRAIL), ("paste", "past"))
    if c is not None:
        words = c[0].lower().replace(",", " ").split()
        prev = words[-1] if words else ""
        if prev == "and" and len(words) > 1 and words[-2] in ("copy", "cut"):
            prev = "copy"
        if prev not in _PASTE_TEXT_AFTER:
            paste = c[0]
    if not send and paste is None and not shots:
        return None
    return t, send, paste, shots


def _maybe_send(s):
    """Text to keep in place of a chunk whose last word is "send" (used by the tests)."""
    r = _parse(s)
    return r[0] if r and r[1] else None


class VoiceSendPlugin(BasePlugin):

    def create_settings(self):
        return [
            Header(text="Voice Send"),
            Switch(key="enabled", text="Say \"send\" to send", default=True,
                   subtext="While dictating with Gboard voice typing, saying send sends the message like the send button.",
                   on_change=self._on_toggle),
            Switch(key="paste", text="Say \"paste\" to paste", default=True,
                   subtext="While dictating, saying paste as the last word puts the clipboard text in the message box instead of the word."),
            Switch(key="screenshot", text="Say \"screenshot\" to attach", default=True,
                   subtext="While dictating, saying screenshot as the last word attaches your newest screenshot to the message you are writing. Say it again for the one before it. Nothing is sent until you send.",
                   on_change=self._on_shot_toggle),
            Switch(key="picture", text="Say \"picture\" to attach", default=True,
                   subtext="While dictating, saying picture as the last word attaches the newest photo from your camera roll to the message you are writing. Say it again for the one before it. Nothing is sent until you send."),
            Switch(key="clip_hold", text="Hold the paperclip for screenshots", default=True,
                   subtext="A long press on the paperclip attaches your newest screenshot and keeps adding the one before it while you hold. A short tap opens the attach menu as before.",
                   on_change=self._on_clip_toggle),
        ]

    def on_plugin_load(self):
        self.errors = 0
        self.fired = 0
        self.in_commit = 0
        self.debug = False
        self.ctx = None
        WeakHashMap = jclass("java.util.WeakHashMap")
        Collections = jclass("java.util.Collections")
        self.fields = Collections.synchronizedMap(WeakHashMap())
        self.ics = Collections.synchronizedMap(WeakHashMap())
        self.VISIBLE = 0
        self.SystemClock = jclass("android.os.SystemClock")
        self.BIC = jclass("android.view.inputmethod.BaseInputConnection")
        caev = find_class("org.telegram.ui.Components.ChatActivityEnterView")
        tv = find_class("android.widget.TextView")
        icw = find_class("android.view.inputmethod.InputConnectionWrapper")
        if caev is None or tv is None or icw is None:
            _log("classes not found")
            return
        self.caev = caev
        plugin = self
        try:
            import os
            ctx = jclass("org.telegram.messenger.ApplicationLoader").applicationContext
            self.ctx = ctx
            self.audio = ctx.getSystemService("audio")
            self.debug = os.path.exists(os.path.join(str(ctx.getFilesDir().getAbsolutePath()), "voice_send_debug"))
        except Exception as e:
            self.audio = None
            self._err("audio", e)
        self.mic = None
        try:
            self.mic = self._load_micwatch(ctx)
            self.mic.start(self.audio)
        except Exception as e:
            self.mic = None
            self._err("micwatch", e)
        self.tray = None
        try:
            if self.mic is not None:
                self.tray = self._tray_static(self.loader.loadClass(SHOTTRAY))
        except Exception as e:
            self.tray = None
            self._err("shottray", e)
        self.clip = None
        try:
            if self.tray is not None:
                self.clip = self.loader.loadClass(CLIPHOLD)
                self.clip.getField("enabled").setBoolean(None, bool(self.get_setting("clip_hold", True)))
                V = jclass("android.view.View")
                self.clip_arm = self.clip.getMethod("arm", V, V, jclass("android.widget.EditText"),
                                                    jclass("android.view.ViewGroup"), jclass("java.lang.Object"))
        except Exception as e:
            self.clip = None
            self._err("cliphold", e)

        class CreateHook(MethodHook):
            def after_hooked_method(self, param):
                try:
                    plugin._prep(get_private_field(param.thisObject, "messageEditText"))
                except Exception as e:
                    plugin._err("create", e)

        class ActionHook(MethodHook):
            def before_hooked_method(self, param):
                try:
                    if int(param.args[0]) != IME_ACTION_SEND or not plugin.fields.containsKey(param.thisObject):
                        return
                    if not plugin.get_setting("enabled", True):
                        return
                    if plugin._recent():
                        param.setResult(True)
                        return
                    plugin._send(param.thisObject)
                    param.setResult(True)
                except Exception as e:
                    plugin._err("send", e)

        class IcHook(MethodHook):
            """The message field's onCreateInputConnection: remember the connection it hands out."""
            def after_hooked_method(self, param):
                try:
                    ic = param.getResult()
                    if plugin.debug:
                        _log("trace ic %s known=%s" % (ic.getClass().getName() if ic is not None else None, plugin.fields.containsKey(param.thisObject)))
                    if ic is not None and plugin.fields.containsKey(param.thisObject):
                        plugin.ics.put(ic, param.thisObject)
                except Exception as e:
                    plugin._err("ic", e)

        class CommitHook(MethodHook):
            def before_hooked_method(self, param):
                try:
                    field = plugin.ics.get(param.thisObject)
                    if field is None:
                        return
                    s = param.args[0]
                    s = str(s) if s is not None else None
                    if plugin.debug:
                        plugin._trace("commit", s, field)
                    if not s or not _hint(s):
                        return
                    r = plugin._read(s, field)
                    if r is None or not plugin.get_setting("enabled", True):
                        return
                    if r[1] and r[2] is None and not r[3] and plugin._recent():
                        param.args[0] = r[0]
                        return
                    if not plugin._mic_on():
                        return
                    text = plugin._apply(r)
                    if text is None:
                        return
                    if r[3]:
                        plugin._shoot(field, r[3])
                    plugin.in_commit = plugin.SystemClock.uptimeMillis()
                    param.args[0] = text
                    if r[1]:
                        plugin._fire(field)
                except Exception as e:
                    plugin._err("commit", e)

            def after_hooked_method(self, param):
                plugin.in_commit = 0

        class FinishHook(MethodHook):
            def before_hooked_method(self, param):
                try:
                    field = plugin.ics.get(param.thisObject)
                    if field is None:
                        return
                    if plugin.in_commit and plugin.SystemClock.uptimeMillis() - plugin.in_commit < 500:
                        return  # the field finishing its own text inside a commit that was just handled
                    ed = field.getText()
                    a = plugin.BIC.getComposingSpanStart(ed)
                    b = plugin.BIC.getComposingSpanEnd(ed)
                    if a < 0 or b <= a:
                        return
                    s = str(ed.subSequence(max(a, b - 8), b))
                    if not _hint(s):
                        if plugin.debug:
                            plugin._trace("finish", s, field)
                        return
                    s = str(ed.subSequence(a, b))
                    if plugin.debug:
                        plugin._trace("finish", s, field)
                    r = plugin._read(s, field)
                    if r is None or not plugin.get_setting("enabled", True):
                        return
                    recent = r[1] and r[2] is None and not r[3] and plugin._recent()
                    if not recent and not plugin._mic_on():
                        return
                    text = plugin._apply(r)
                    if text is None:
                        return
                    if r[3]:
                        plugin._shoot(field, r[3])
                    ed.replace(a, b, text)
                    if r[1] and not recent:
                        plugin._fire(field)
                except Exception as e:
                    plugin._err("finish", e)

        class SendHook(MethodHook):
            """Every way of sending the box ends here. Screenshots waiting: they go out as an album."""
            def before_hooked_method(self, param):
                try:
                    ev = param.thisObject
                    if plugin.tray is None or not plugin.caev.isInstance(ev):
                        return
                    if plugin.tray.busy(ev):
                        plugin.tray.when_ready(ev, plugin._r2(lambda: plugin._again(ev)))
                        param.setResult(False)
                        return
                    if plugin.tray.count(ev) <= 0 or ev.isEditingMessage():
                        return
                    if plugin._send_media(ev, bool(param.args[0]), int(param.args[1])):
                        param.setResult(False)
                except Exception as e:
                    plugin._err("send hook", e)

        class ComposeTrace(MethodHook):
            def before_hooked_method(self, param):
                try:
                    field = plugin.ics.get(param.thisObject)
                    if field is not None:
                        s = param.args[0]
                        plugin._trace("compose", str(s) if s is not None else None, field)
                except Exception as e:
                    plugin._err("trace", e)

        create = None
        for m in caev.getDeclaredMethods():
            if m.getName() == "createMessageEditText" and len(m.getParameterTypes()) == 0:
                create = m
        if create is None:
            _log("createMessageEditText not found")
            return
        CS = jclass("java.lang.CharSequence")
        INT = jclass("java.lang.Integer").TYPE
        self.ic_hook = IcHook()
        self.ic_classes = set()
        self.hook_method(create, CreateHook())
        self.hook_method(tv.getDeclaredMethod("onEditorAction", INT), ActionHook())
        # The keyboard talks to the field through the androidx InputConnectionCompat wrapper that the
        # field's own onCreateInputConnection returns (a subclass of the framework
        # InputConnectionWrapper). Calls into it arrive by interface dispatch, so hooks on
        # InputConnectionWrapper see every one of them; a hook on BaseInputConnection would not,
        # because EditableInputConnection reaches it by a direct super call inside the boot image.
        self.hook_method(icw.getDeclaredMethod("commitText", CS, INT), CommitHook())
        self.hook_method(icw.getDeclaredMethod("finishComposingText"), FinishHook())
        if self.tray is not None:
            try:
                smi = None
                for m in caev.getDeclaredMethods():
                    if m.getName() == "sendMessageInternal" and len(m.getParameterTypes()) == 6:
                        smi = m
                if smi is not None:
                    self.hook_method(smi, SendHook())
                else:
                    self.tray = None
                    _log("sendMessageInternal not found, spoken screenshot and picture are off")
            except Exception as e:
                self.tray = None
                self._err("send hook", e)
        if self.tray is None:
            self.clip = None
        if self.debug:
            self.hook_method(icw.getDeclaredMethod("setComposingText", CS, INT), ComposeTrace())
        AU = jclass("org.telegram.messenger.AndroidUtilities")
        AU.runOnUIThread(self._r(self._prep_open))
        _log("loaded %s%s" % (__version__, " debug" if self.debug else ""))

    def _load_micwatch(self, ctx):
        """The tiny MicWatch class ships as a dex inside this file (see the end of the file)."""
        import base64
        import zlib
        with open(__file__, "r", encoding="utf-8") as fh:
            text = fh.read()
        block = text.rsplit("# __DEX_BEGIN__", 1)[1].split("# __DEX_END__", 1)[0]
        payload = "".join(l.strip()[1:].strip() for l in block.splitlines() if l.strip().startswith("#"))
        data = zlib.decompress(base64.b64decode(payload))
        Loader = jclass("dalvik.system.InMemoryDexClassLoader")
        self.loader = Loader(jclass("java.nio.ByteBuffer").wrap(data), ctx.getClassLoader())
        return self._static(self.loader.loadClass(MICWATCH))

    def _static(self, cls):
        """Wrap a java.lang.Class so its static methods can be called from Python."""
        AM = jclass("android.media.AudioManager")
        start = cls.getMethod("start", AM)
        stop = cls.getMethod("stop")
        recent = cls.getMethod("recent", jclass("java.lang.Long").TYPE)

        class Static:
            def start(self, am):
                start.invoke(None, am)

            def stop(self):
                stop.invoke(None)

            def recent(self, ms):
                r = recent.invoke(None, jclass("java.lang.Long")(int(ms)))
                return r if isinstance(r, bool) else bool(r.booleanValue())
        return Static()

    def _tray_static(self, cls):
        """Wrap the ShotTray class so its static methods can be called from Python."""
        V = jclass("android.view.View")
        INT = jclass("java.lang.Integer").TYPE
        Integer = jclass("java.lang.Integer")
        add = cls.getMethod("add", V, jclass("android.widget.EditText"), jclass("android.view.ViewGroup"),
                            jclass("java.lang.Object"), INT, INT)
        count = cls.getMethod("count", V)
        busy = cls.getMethod("busy", V)
        ready = cls.getMethod("whenReady", V, jclass("java.lang.Runnable"))
        take = cls.getMethod("take", V)
        clear = cls.getMethod("clear", V)
        clear_all = cls.getMethod("clearAll")
        quiet = cls.getMethod("quiet", V)

        def _b(r):
            return r if isinstance(r, bool) else bool(r.booleanValue())

        class Static:
            def add(self, ev, field, content, bubble, n, kind):
                add.invoke(None, ev, field, content, bubble, Integer(int(n)), Integer(int(kind)))

            def count(self, ev):
                r = count.invoke(None, ev)
                return r if isinstance(r, int) else int(r.intValue())

            def busy(self, ev):
                return _b(busy.invoke(None, ev))

            def when_ready(self, ev, r):
                ready.invoke(None, ev, r)

            def take(self, ev):
                r = take.invoke(None, ev)
                return [str(x) for x in r] if r is not None else []

            def clear(self, ev):
                clear.invoke(None, ev)

            def clear_all(self):
                clear_all.invoke(None)

            def quiet(self, ev):
                quiet.invoke(None, ev)
        return Static()

    def _r2(self, fn):
        from android_utils import R
        r = R(fn)
        self._keep2 = getattr(self, "_keep2", [])[-7:] + [r]
        return r

    def _r(self, fn):
        from android_utils import R
        self._keep = R(fn)
        return self._keep

    def on_plugin_unload(self):
        try:
            if self.clip is not None:
                self.clip.getField("enabled").setBoolean(None, False)
                disarm = self.clip.getMethod("disarmAll")
                jclass("org.telegram.messenger.AndroidUtilities").runOnUIThread(self._r2(lambda: disarm.invoke(None)))
        except Exception as e:
            self._err("clip stop", e)
        try:
            if self.tray is not None:
                self.tray.clear_all()
        except Exception as e:
            self._err("tray stop", e)
        try:
            if self.mic is not None:
                self.mic.stop()
        except Exception as e:
            self._err("micwatch stop", e)
        try:
            for f in list(self.fields.keySet().toArray()):
                if f is not None:
                    f.setPrivateImeOptions(_without(f.getPrivateImeOptions()))
        except Exception as e:
            self._err("unload", e)

    def _err(self, where, e):
        if self.errors < 5:
            self.errors += 1
            _log("%s: %s" % (where, e))

    def _trace(self, what, s, field):
        if s is not None and len(s) > 20:
            s = "<%d chars>...%s" % (len(s), s[-10:])
        _log("trace %s %r mic=%s" % (what, s, self._mic_on()))

    def _apply(self, r):
        """Text that replaces the finished chunk, or None to leave it as dictated."""
        keep, send, paste, shots = r
        if paste is None or not self.get_setting("paste", True):
            return keep if send or shots else None
        clip = self._clip()
        if not clip:
            return keep if send or shots else None  # empty clipboard: "paste" stays as text
        base = _tidy(paste)
        text = (base + " " + clip) if base.strip() else (paste + clip)
        _log("spoken paste: word dropped, clipboard text inserted")
        return text

    def _read(self, s, field):
        """_parse with the screenshot command on only where it can work."""
        on = self.tray is not None and self.get_setting("screenshot", True)
        pic = self.tray is not None and self.get_setting("picture", True)
        r = _parse(s, on, pic)
        if r is not None and r[3]:
            ev = self._outer(field)
            if ev is None or ev.isEditingMessage() or get_private_field(ev, "parentFragment") is None:
                r = _parse(s, False, False)
        return r

    def _shoot(self, field, kinds):
        """Spoken "screenshot" or "picture": ask the tray of this message box for the next newest ones."""
        ev = self._outer(field)
        chat = get_private_field(ev, "parentFragment")
        bubble = None
        try:
            bubble = get_private_field(chat, "chatInputViewsContainer")
        except Exception:
            pass
        content = chat.getFragmentView()
        i = 0
        while i < len(kinds):
            j = i
            while j < len(kinds) and kinds[j] == kinds[i]:
                j += 1
            self.tray.add(ev, field, content, bubble, j - i, kinds[i])
            i = j
        _log("spoken attach: word dropped, %d screenshot, %d picture asked" % (kinds.count(0), kinds.count(1)))

    def _again(self, ev):
        try:
            ev.sendMessage()
        except Exception as e:
            self._err("send again", e)

    def _send_media(self, ev, notify, schedule):
        """Send the waiting screenshots as one album, the text in the box as the caption.

        The same call the app makes for picked photos. True when the box was sent this way."""
        chat = get_private_field(ev, "parentFragment")
        if chat is None:
            return False
        if not chat.checkSlowModeAlert():
            return True  # the slow mode notice is up; the tray stays as it is
        text = ev.getFieldText()
        cap = str(text).strip() if text is not None else ""
        paths = self.tray.take(ev)
        if not paths:
            return False
        limit = CAPTION_MAX
        try:
            limit = int(chat.getMessagesController().getCaptionMaxLengthLimit())
        except Exception:
            pass
        over = len(cap) > limit
        Info = jclass("org.telegram.messenger.SendMessagesHelper$SendingMediaInfo")
        lst = jclass("java.util.ArrayList")()
        for i, p in enumerate(paths):
            info = Info()
            info.path = p
            if i == 0 and cap and not over:
                info.caption = cap
                try:
                    from java import jarray
                    arr = jarray(jclass("java.lang.CharSequence"))([jclass("org.telegram.messenger.AndroidUtilities").getTrimmedString(text)])
                    ents = jclass("org.telegram.messenger.MediaDataController").getInstance(chat.getCurrentAccount()).getEntities(arr, True)
                    info.caption = str(arr[0])
                    info.entities = ents
                except Exception as e:
                    self._err("caption", e)
            lst.add(info)
        chat.didSelectSearchPhotos(lst, notify, schedule)
        _log("attachments sent: %d%s" % (len(paths), " with caption" if cap and not over else ""))
        if over:
            return False  # too long for a caption: the text follows as its own message
        ev.setFieldText("")
        return True

    def _arm_clip(self, field):
        try:
            ev = self._outer(field)
            chat = get_private_field(ev, "parentFragment") if ev is not None else None
            if chat is None:
                return
            btn = ev.getAttachButton()
            content = chat.getFragmentView()
            if btn is None or content is None:
                return
            bubble = None
            try:
                bubble = get_private_field(chat, "chatInputViewsContainer")
            except Exception:
                pass
            self.clip_arm.invoke(None, btn, ev, field, content, bubble)
        except Exception as e:
            self._err("arm clip", e)

    def _on_clip_toggle(self, enabled):
        try:
            if self.clip is not None:
                self.clip.getField("enabled").setBoolean(None, bool(enabled))
        except Exception as e:
            self._err("clip toggle", e)

    def _on_shot_toggle(self, enabled):
        try:
            if not enabled and self.tray is not None:
                self.tray.clear_all()
        except Exception as e:
            self._err("shot toggle", e)

    def _clip(self):
        """The clipboard's text, read only at the moment a spoken paste fires. Never logged."""
        try:
            cm = self.ctx.getSystemService("clipboard")
            if self.debug:
                _log("trace clip has=%s" % (cm is not None and cm.hasPrimaryClip()))
            if cm is None or not cm.hasPrimaryClip():
                return None
            c = cm.getPrimaryClip()
            if c is None or c.getItemCount() < 1:
                return None
            t = c.getItemAt(0).coerceToText(self.ctx)
            return str(t) if t is not None and len(t) > 0 else None
        except Exception as e:
            self._err("clip", e)
            return None

    def _recent(self):
        return self.fired and self.SystemClock.uptimeMillis() - self.fired < GUARD_MS

    def _mic_on(self):
        """Is voice typing on: the microphone open now or closed under VOICE_GRACE_MS ago?"""
        try:
            if self.mic is not None:
                return bool(self.mic.recent(VOICE_GRACE_MS))
            return self.audio is not None and not self.audio.getActiveRecordingConfigurations().isEmpty()
        except Exception as e:
            self._err("mic", e)
            return False

    def _fire(self, field):
        """Press send once the keyboard's current call has applied (the word is already dropped)."""
        self.fired = self.SystemClock.uptimeMillis()
        _log("spoken send: word dropped, pressing send")
        plugin = self

        def go():
            try:
                plugin._send(field)
            except Exception as e:
                plugin._err("fire", e)
        from android_utils import R
        self._fire_r = R(go)
        jclass("org.telegram.messenger.AndroidUtilities").runOnUIThread(self._fire_r)

    def _prep(self, field):
        """Once per message field: tell the keyboard this field takes a send action."""
        if field is None:
            return
        self.fields.put(field, True)
        cls = field.getClass()
        name = str(cls.getName())
        if name not in self.ic_classes:
            self.ic_classes.add(name)
            self._hook_ic(cls)
        if self.clip is not None:
            # the paperclip is made after the message field: arm it once the box is built
            jclass("org.telegram.messenger.AndroidUtilities").runOnUIThread(self._r2(lambda: self._arm_clip(field)))
        if not self.get_setting("enabled", True):
            return
        opts = field.getPrivateImeOptions()
        cur = str(opts) if opts is not None else ""
        if TOKEN not in cur.replace(";", ",").split(","):
            field.setPrivateImeOptions(TOKEN if not cur else cur + "," + TOKEN)
        if field.hasFocus():
            imm = field.getContext().getSystemService("input_method")
            if imm is not None:
                imm.restartInput(field)

    def _hook_ic(self, cls):
        """Hook the nearest app override of onCreateInputConnection (the one that wraps the connection)."""
        EI = jclass("android.view.inputmethod.EditorInfo")
        c = cls
        while c is not None and not str(c.getName()).startswith("android."):
            try:
                m = c.getDeclaredMethod("onCreateInputConnection", EI)
                key = str(c.getName()) + "#ic"
                if key not in self.ic_classes:
                    self.ic_classes.add(key)
                    self.hook_method(m, self.ic_hook)
                return
            except Exception:
                c = c.getSuperclass()
        _log("onCreateInputConnection not found for %s" % cls.getName())

    def _prep_open(self):
        """At load: the chats already open get the option too."""
        try:
            f = get_last_fragment()
            stack = f.getParentLayout().getFragmentStack() if f is not None else None
            if stack is None:
                return
            for i in range(stack.size()):
                ev = get_private_field(stack.get(i), "chatActivityEnterView")
                if ev is not None:
                    self._prep(get_private_field(ev, "messageEditText"))
        except Exception as e:
            self._err("open chats", e)

    def _on_toggle(self, enabled):
        try:
            for f in list(self.fields.keySet().toArray()):
                if f is None:
                    continue
                if enabled:
                    self._prep(f)
                else:
                    f.setPrivateImeOptions(_without(f.getPrivateImeOptions()))
                    if f.hasFocus():
                        imm = f.getContext().getSystemService("input_method")
                        if imm is not None:
                            imm.restartInput(f)
        except Exception as e:
            self._err("toggle", e)

    def _outer(self, field):
        c = field.getClass()
        while c is not None:
            try:
                fl = c.getDeclaredField("this$0")
                fl.setAccessible(True)
                v = fl.get(field)
                if self.caev.isInstance(v):
                    return v
            except Exception:
                pass
            c = c.getSuperclass()
        return None

    def _shown(self, v):
        return v is not None and v.getVisibility() == self.VISIBLE and v.isShown() and float(v.getAlpha()) > 0.5

    def _send(self, field):
        """Press the button a finger would press now."""
        ev = self._outer(field)
        if ev is None:
            return
        if self.tray is not None and self.tray.busy(ev):
            self.tray.when_ready(ev, self._r2(lambda: self._send(field)))
            return
        if ev.isEditingMessage():
            done = get_private_field(ev, "doneButton")
            if self._shown(done):
                done.performClick()
            else:
                ev.doneEditingMessage()
            return
        sb = ev.getSendButtonInternal()
        if self._shown(sb) and sb.isEnabled():
            sb.performClick()
            return
        slow = get_private_field(ev, "slowModeButton")
        if self._shown(slow):
            slow.performClick()
            return
        # The field was empty a moment ago: the send button is still fading in, so call the
        # same method the button calls.
        try:
            m = self.caev.getDeclaredMethod("sendMessage")
            m.setAccessible(True)
            m.invoke(ev)
            _log("send button still appearing, sent through sendMessage")
        except Exception as e:
            self._err("send", e)
