(() => {
  // Where the download buttons go. Flip to true once each store page is live.
  const LINKS = {
    play: { live: false, url: "https://play.google.com/store/apps/details?id=dev.kaff.app" },
    windows: { live: false, url: "https://github.com/yazanprogrammer1/kaff/releases/latest" },
  };
  const SOON = { ar: "قريباً", en: "Soon" };

  const root = document.documentElement;
  const store = {
    get: k => { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set: (k, v) => { try { localStorage.setItem(k, v); } catch (e) { } },
  };

  // Language: URL ?lang=, saved choice, then the browser.
  const langBtn = document.getElementById("lang");
  const titles = { ar: "كفّ · انقل ملفاتك بحركة يدك", en: "Kaff · Move files with a gesture" };
  function setLang(l) {
    root.dataset.lang = l; root.lang = l; root.dir = l === "ar" ? "rtl" : "ltr";
    langBtn.textContent = l === "ar" ? "EN" : "ع";
    document.title = titles[l];
    document.querySelectorAll(".soon").forEach(s => { s.textContent = SOON[l]; });
  }
  const urlLang = new URLSearchParams(location.search).get("lang");
  const navLang = (navigator.language || "").toLowerCase().startsWith("en") ? "en" : "ar";
  setLang(urlLang === "en" || urlLang === "ar" ? urlLang : store.get("kaff-lang") || navLang);
  langBtn.addEventListener("click", () => { const l = root.dataset.lang === "ar" ? "en" : "ar"; setLang(l); store.set("kaff-lang", l); });

  // Theme: system by default, toggle remembers.
  const saved = store.get("kaff-theme");
  if (saved) root.dataset.theme = saved;
  document.getElementById("theme").addEventListener("click", () => {
    const dark = root.dataset.theme ? root.dataset.theme === "dark" : matchMedia("(prefers-color-scheme: dark)").matches;
    root.dataset.theme = dark ? "light" : "dark";
    store.set("kaff-theme", root.dataset.theme);
  });

  // Download buttons: real links when live, a quiet "soon" badge before.
  function wire(selector, link) {
    document.querySelectorAll(selector).forEach(a => {
      if (link.live) {
        a.href = link.url; a.target = "_blank"; a.rel = "noopener";
      } else {
        a.href = "#download"; a.setAttribute("aria-disabled", "true");
        const b = a.querySelector("b");
        if (b && !b.querySelector(".soon")) b.insertAdjacentHTML("beforeend", `<span class="soon">${SOON[root.dataset.lang]}</span>`);
      }
    });
  }
  wire("[data-play]", LINKS.play);
  wire("[data-win]", LINKS.windows);

  // Nav hairline once scrolled; reveal sections as they enter.
  const nav = document.getElementById("nav");
  addEventListener("scroll", () => nav.classList.toggle("scrolled", scrollY > 8), { passive: true });
  if ("IntersectionObserver" in window) {
    const io = new IntersectionObserver(entries => entries.forEach(e => {
      if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); }
    }), { rootMargin: "0px 0px -8% 0px" });
    document.querySelectorAll(".reveal").forEach((el, i) => { el.style.transitionDelay = `${(i % 3) * 70}ms`; io.observe(el); });
  } else {
    document.querySelectorAll(".reveal").forEach(el => el.classList.add("in"));
  }
})();
