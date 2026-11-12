"""Builds voice_send.plugin: voice_send.src.py plus the dex of the Java classes as a base64 comment block.

javac -source 8 -target 8 -cp <sdk>/platforms/android-35/android.jar -d out java/com/firestarter/voicesend/*.java
d8 --min-api 26 --lib <sdk>/platforms/android-35/android.jar --output build out/com/firestarter/voicesend/*.class
python3 build.py
"""
import base64
import os
import sys
import zlib

d = os.path.dirname(os.path.abspath(__file__))
dex = sys.argv[1] if len(sys.argv) > 1 else os.path.join(d, "build", "classes.dex")
src = open(os.path.join(d, "voice_send.src.py"), encoding="utf-8").read()
b = base64.b64encode(zlib.compress(open(dex, "rb").read(), 9)).decode()
lines = ["# " + b[i:i + 100] for i in range(0, len(b), 100)]
with open(os.path.join(d, "voice_send.plugin"), "w", encoding="utf-8") as fh:
    fh.write(src.rstrip() + "\n\n# __DEX_BEGIN__\n" + "\n".join(lines) + "\n# __DEX_END__\n")
print("ok", len(b))
