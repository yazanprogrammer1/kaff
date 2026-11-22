"""Frames the raw store screenshots (screens-raw/) into 1080 × 1920 Play images with captions.

Run after StoreScreenshots: python fastlane/metadata/android/graphics-src/frame_all.py
"""
import os
import subprocess
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

# (raw shot, title with <em>accent</em>, subtitle) per store language.
SHOTS = {
    "ar": [
        ("1_home", "ارفع إيدك… <em>وانقل</em>", "صور وفيديو وتطبيقات، بدون إنترنت."),
        ("2_picker", "اختر <em>كل اللي تبيه</em>", "صور، فيديو، تطبيقات، وملفات."),
        ("3_grab", "اقبض كفّك… <em>وامسكها</em>", "الكاميرا تفهم حركتك داخل جوالك."),
        ("4_receive", "افتح يدك… <em>واستلم</em>", "أو اضغط «قبول»، أنت تختار."),
        ("5_transfer", "سريع <em>بدون إنترنت</em>", "اتصال Wi-Fi مباشر بين الجوالين."),
        ("6_done", "توصل <em>وتنحفظ</em> لحالها", "في المعرض والتنزيلات، مرتّبة."),
        ("7_desktop", "ومع <em>الكمبيوتر</em> كمان", "من المتصفح أو برنامج Windows."),
        ("8_share", "شارك من <em>أي تطبيق</em>", "اختر كفّ، وبعدها جوال أو كمبيوتر."),
    ],
    "en": [
        ("1_home", "Raise your hand… <em>and send</em>", "Photos, videos and apps, no internet."),
        ("2_picker", "Pick <em>anything</em>", "Photos, videos, apps and files."),
        ("3_grab", "Make a fist… <em>grab it</em>", "Your phone reads the gesture on-device."),
        ("4_receive", "Open your palm… <em>catch it</em>", "Or tap Accept. Your call."),
        ("5_transfer", "Fast, <em>no internet</em>", "A direct Wi-Fi link between phones."),
        ("6_done", "Saved <em>by itself</em>", "Into Gallery and Downloads, organised."),
        ("7_desktop", "And your <em>computer</em> too", "From any browser or the Windows app."),
        ("8_share", "Share from <em>any app</em>", "Pick Kaff, then a phone or a computer."),
    ],
}
FOLDERS = {"ar": "ar", "en": "en-US"}

frame = "file:///" + os.path.join(HERE, "frame.html").replace("\\", "/")
for lang, shots in SHOTS.items():
    out = os.path.join(HERE, "..", FOLDERS[lang], "images", "phoneScreenshots")
    os.makedirs(out, exist_ok=True)
    for i, (shot, title, sub) in enumerate(shots, 1):
        url = frame + "?" + urllib.parse.urlencode({"lang": lang, "shot": shot, "t": title, "s": sub})
        target = os.path.abspath(os.path.join(out, f"{i}.png"))
        subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--allow-file-access-from-files",
                        "--virtual-time-budget=3000", "--window-size=1080,1920", f"--screenshot={target}", url],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        print(lang, i, os.path.exists(target))
