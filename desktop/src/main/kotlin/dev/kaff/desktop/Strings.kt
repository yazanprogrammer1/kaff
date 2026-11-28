package dev.kaff.desktop

/** Every visible text, Arabic first. [t] follows the chosen language. */
interface Texts {
    val rtl: Boolean
    val appName: String
    val searchingTitle: String
    val searchingBody: String
    val searchingHint: String
    val notFoundTitle: String
    val notFoundBody: String
    val phonesFound: String
    val connect: String
    val waitingApproval: String
    val waitingApprovalBody: String
    val denied: String
    val kaffNetworkTitle: String
    val kaffNetworkBody: String
    val join: String
    val password: String
    val passwordHint: String
    val joining: String
    val joinFailed: String
    val connectedTitle: String
    val viaKaff: String
    val viaWifi: String
    val disconnect: String
    val fromPhone: String
    val fromPhoneEmpty: String
    val toPhone: String
    val dropTitle: String
    val dropBody: String
    val chooseFiles: String
    val openFolder: String
    val open: String
    val textsTitle: String
    val textHint: String
    val send: String
    val copy: String
    val copied: String
    val fromPhoneClip: String
    val fromComputerClip: String
    val waiting: String
    val done: String
    val failed: String
    val cancelled: String
    val fasterTitle: String
    val fasterBody: String
    val cantSeeIt: String
    val switched: String
    val language: String
    val newFilesTitle: (Int) -> String
    val clipTitle: String
    val left: (Long) -> String
}

object Ar : Texts {
    override val rtl = true
    override val appName = "كفّ"
    override val searchingTitle = "نبحث عن جوالك…"
    override val searchingBody = "افتح كفّ في جوالك واضغط «إلى الكمبيوتر». بيظهر هنا لحاله."
    override val searchingHint = "الجوال والكمبيوتر على نفس الواي فاي، أو اتصل بشبكة كفّ تحت."
    override val notFoundTitle = "الجوال ما ظهر؟"
    override val notFoundBody = "بعض الراوترات تمنع الأجهزة من رؤية بعضها (عزل الأجهزة). الحل الأسرع: في جوالك، داخل شاشة «إلى الكمبيوتر»، اختر «شبكة كفّ»، وبتظهر هنا خلال ثواني."
    override val phonesFound = "جوالات قريبة"
    override val connect = "اتصال"
    override val waitingApproval = "وافق من جوالك"
    override val waitingApprovalBody = "اضغط «سماح» في الجوال. أول مرة بس، بعدها يتصل لحاله."
    override val denied = "ما تمت الموافقة. حاول مرة ثانية."
    override val kaffNetworkTitle = "شبكة كفّ قريبة"
    override val kaffNetworkBody = "اتصال مباشر مع الجوال بدون راوتر، وأسرع."
    override val join = "اتصل بالشبكة"
    override val password = "كلمة السر"
    override val passwordHint = "تلقاها في الجوال تحت «شبكة كفّ». تكتبها مرة وحدة بس."
    override val joining = "نتصل…"
    override val joinFailed = "ما قدرنا نتصل. تأكد من كلمة السر وإن الشبكة شغّالة في الجوال."
    override val connectedTitle = "متصل"
    override val viaKaff = "عبر شبكة كفّ"
    override val viaWifi = "عبر الواي فاي"
    override val disconnect = "قطع الاتصال"
    override val fromPhone = "من الجوال"
    override val fromPhoneEmpty = "اللي ترسله من الجوال ينزل هنا لحاله، في مجلد التنزيلات › Kaff."
    override val toPhone = "إلى الجوال"
    override val dropTitle = "اسحب الملفات وأفلتها هنا"
    override val dropBody = "أي نوع وأي حجم · تنحفظ في المعرض أو التنزيلات"
    override val chooseFiles = "اختيار ملفات"
    override val openFolder = "فتح المجلد"
    override val open = "فتح"
    override val textsTitle = "نصوص وروابط"
    override val textHint = "اكتب أو الصق، واضغط Enter"
    override val send = "إرسال"
    override val copy = "نسخ"
    override val copied = "تم النسخ"
    override val fromPhoneClip = "من الجوال"
    override val fromComputerClip = "من هذا الكمبيوتر"
    override val waiting = "بالانتظار"
    override val done = "تم ✓"
    override val failed = "ما اكتمل"
    override val cancelled = "انلغى من الجوال"
    override val fasterTitle = "تبي سرعة أعلى بكثير؟"
    override val fasterBody = "اتصل بشبكة كفّ المباشرة، والبرنامج يكمل معك لحاله."
    override val cantSeeIt = "ما تظهر الشبكة؟"
    override val switched = "حوّلنا الشبكة لوضع 2.4GHz. جرّب بعد ثواني."
    override val language = "English"
    override val newFilesTitle = { n: Int -> if (n == 1) "وصل ملف من الجوال" else "وصلت $n ملفات من الجوال" }
    override val clipTitle = "نص من الجوال"
    override val left = { s: Long -> if (s < 60) "باقي $s ث" else "باقي ${s / 60} د" }
}

object En : Texts {
    override val rtl = false
    override val appName = "Kaff"
    override val searchingTitle = "Looking for your phone…"
    override val searchingBody = "Open Kaff on your phone and tap \"To a computer\". It shows up here by itself."
    override val searchingHint = "Phone and computer on the same Wi-Fi, or join Kaff network below."
    override val notFoundTitle = "Phone not showing up?"
    override val notFoundBody = "Some routers stop devices from seeing each other (client isolation). The quickest fix: on your phone, in \"To a computer\", choose \"Kaff network\". It shows up here in a few seconds."
    override val phonesFound = "Nearby phones"
    override val connect = "Connect"
    override val waitingApproval = "Approve on your phone"
    override val waitingApprovalBody = "Tap \"Allow\" on the phone. Only the first time; after that it connects by itself."
    override val denied = "Not approved. Try again."
    override val kaffNetworkTitle = "Kaff network nearby"
    override val kaffNetworkBody = "A direct link to the phone, no router, faster."
    override val join = "Join network"
    override val password = "Password"
    override val passwordHint = "Shown on the phone under \"Kaff network\". You type it once."
    override val joining = "Connecting…"
    override val joinFailed = "Could not connect. Check the password and that the network is on."
    override val connectedTitle = "Connected"
    override val viaKaff = "via Kaff network"
    override val viaWifi = "via Wi-Fi"
    override val disconnect = "Disconnect"
    override val fromPhone = "From the phone"
    override val fromPhoneEmpty = "What you send from the phone lands here by itself, in Downloads › Kaff."
    override val toPhone = "To the phone"
    override val dropTitle = "Drop files here"
    override val dropBody = "Any type, any size · saved to Gallery or Downloads"
    override val chooseFiles = "Choose files"
    override val openFolder = "Open folder"
    override val open = "Open"
    override val textsTitle = "Text & links"
    override val textHint = "Type or paste, then Enter"
    override val send = "Send"
    override val copy = "Copy"
    override val copied = "Copied"
    override val fromPhoneClip = "From the phone"
    override val fromComputerClip = "From this computer"
    override val waiting = "Waiting"
    override val done = "Done ✓"
    override val failed = "Incomplete"
    override val cancelled = "Cancelled on the phone"
    override val fasterTitle = "Want it much faster?"
    override val fasterBody = "Join Kaff's direct network; the app follows you there."
    override val cantSeeIt = "Can't see the network?"
    override val switched = "Switched the network to 2.4 GHz. Try again in a few seconds."
    override val language = "العربية"
    override val newFilesTitle = { n: Int -> if (n == 1) "A file arrived from the phone" else "$n files arrived from the phone" }
    override val clipTitle = "Text from the phone"
    override val left = { s: Long -> if (s < 60) "${s}s left" else "${s / 60} min left" }
}

object Strings {
    @Volatile var t: Texts = Ar
}
