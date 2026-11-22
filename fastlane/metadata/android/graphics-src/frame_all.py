"""Frames the raw store screenshots (screens-raw/) into 1080 × 1920 Play images with captions.

Run after StoreScreenshots: python fastlane/metadata/android/graphics-src/frame_all.py
"""
import os
import subprocess
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "ar", "images", "phoneScreenshots")
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

SHOTS = [
    ("1_home", "ارفع إيدك… <em>وانقل</em>", "صور وفيديو وتطبيقات، بدون إنترنت."),
    ("2_picker", "اختر <em>كل اللي تبيه</em>", "صور، فيديو، تطبيقات، وملفات."),
    ("3_grab", "اقبض كفّك… <em>وامسكها</em>", "الكاميرا تفهم حركتك داخل جوالك."),
    ("4_receive", "افتح يدك… <em>واستلم</em>", "أو اضغط «قبول»، أنت تختار."),
    ("5_transfer", "سريع <em>بدون إنترنت</em>", "اتصال Wi-Fi مباشر بين الجوالين."),
    ("6_done", "توصل <em>وتنحفظ</em> لحالها", "في المعرض والتنزيلات، مرتّبة."),
    ("7_desktop", "ومع <em>الكمبيوتر</em> كمان", "من المتصفح أو برنامج Windows."),
    ("8_share", "شارك من <em>أي تطبيق</em>", "اختر كفّ، وبعدها جوال أو كمبيوتر."),
]

os.makedirs(OUT, exist_ok=True)
frame = "file:///" + os.path.join(HERE, "frame.html").replace("\\", "/")
for i, (shot, title, sub) in enumerate(SHOTS, 1):
    url = frame + "?" + urllib.parse.urlencode({"shot": shot, "t": title, "s": sub})
    target = os.path.abspath(os.path.join(OUT, f"{i}.png"))
    subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--allow-file-access-from-files",
                    "--virtual-time-budget=3000", "--window-size=1080,1920", f"--screenshot={target}", url],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    print(target, os.path.exists(target))
