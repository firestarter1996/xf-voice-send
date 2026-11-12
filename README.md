# 🎙️🗣️📤 Voice Send

A plugin for Nagram XF. While you dictate with Gboard voice typing, say "send" and the message goes out, exactly like tapping the send button.

## Why I made it

Gboard's voice typing understands "send" in Telegram, WhatsApp and a few other apps. In Nagram XF it answers "Command not available in this app" or types the word, so every dictated message still needs a tap. I wanted to finish a message by voice.

## What it changes

* Saying "send" while dictating presses the send button for you: replies, topics, captions, silent send and scheduled messages behave as a tap would.
* It also works right after a spoken punctuation command ("close parenthesis", "period", "question mark", "comma" and so on), where Gboard on its own types the word "send" instead. The word never lands in the message.
* Typed or swiped "send", and "send" inside a longer phrase ("please send it"), stay ordinary text.
* While you edit a message, "send" saves the edit, like the check mark.
* In slow mode it does what tapping the timer does.
* The Enter key still makes a new line, your Send by Enter setting stays as it is, and autocorrect and suggestions are unchanged.
* Work happens once when a chat's message field is created, a short text check once per finished word, and once for each spoken "send". Nothing runs per letter or per frame.

Setting: one switch turns it off.

## Install

1. Use the plugin build of Nagram XF and turn on the plugin engine (Settings, N Settings, Plugins).
2. Download `voice_send.plugin` from the latest release.
3. Send it to your Saved Messages, tap it and choose install. You can also use the plus button on the Plugins screen.
4. Make sure Voice Send is switched on.

## How it works

Gboard runs its voice "send" command only in apps on its own list (the official Telegram package is on it, XF's package is not) or in a field whose EditorInfo says it takes a send action: the IME action SEND, or the private IME option `handlesSendAction`. The plugin hooks `ChatActivityEnterView.createMessageEditText` and adds only `handlesSendAction` to the message field's private IME options, so `imeOptions` and the Enter key stay as they were. Gboard then calls `performEditorAction(IME_ACTION_SEND)`; a hook on `TextView.onEditorAction` answers it for that field only by pressing the send button (the done button while editing, the slow mode button while slow mode counts down).

Gboard does not always treat "send" as a command. Its modeless command detector (a MobileBERT intent classifier with a 0.95 threshold that also reads the text before the cursor and spoken punctuation) often scores "send" as dictation right after a spoken punctuation command, and then simply types the word. So the plugin also watches the message field's own InputConnection (`commitText` and `finishComposingText`, which arrive once per finished word, never per letter) for a finished chunk that is nothing but "send", optionally led by the punctuation Gboard just typed. When that happens while the microphone is recording (`AudioManager.getActiveRecordingConfigurations`, checked only for a matching chunk), the word is dropped and the send button is pressed. Typed or swiped text never has the microphone on, so it is never taken as the command. Errors go to logcat under the tag `VoiceSend`.

## Tested

Nagram XF 12.10.1 plugin build on a Pixel 6 Pro with Gboard 18.5:

* The message field's EditorInfo carries `handlesSendAction`, the action and the no enter action flag are unchanged.
* `performEditorAction(IME_ACTION_SEND)` on the field's own InputConnection sent the text and cleared the field.
* In edit mode the same action saved the edit.
* With Gboard voice typing listening, the field received "VS voice test", then ")", then " send" as separate commits: the message went out without the word "send".
* The same chunk finished through `setComposingText` plus `finishComposingText` also sent.
* With the microphone off, ") send" and "send" stay as typed text, and "please send" never triggers.
* The extra hook costs about 0.15 ms per finished word.

## License

MIT

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=firestarter1996/xf-voice-send&type=Date)](https://star-history.com/#firestarter1996/xf-voice-send&Date)
