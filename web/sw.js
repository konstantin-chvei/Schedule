const SHELL_CACHE = "schedule-shell-v10";
const DATA_CACHE = "schedule-data-v2";
const SHELL_FILES = [
  "./",
  "./index.html",
  "./styles.css",
  "./fonts/RobotoVariable.ttf",
  "./schedule-engine.js",
  "./app.js",
  "./manifest.webmanifest",
  "./ios-install.html",
  "./icons/icon-192.png",
  "./icons/icon-1024.png",
];

const shellPaths = new Set(SHELL_FILES.map((path) => new URL(path, self.location.href).pathname));

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(SHELL_CACHE)
      .then((cache) => cache.addAll(SHELL_FILES))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys
        .filter((key) => ![SHELL_CACHE, DATA_CACHE].includes(key))
        .map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== "GET" || url.origin !== self.location.origin) return;

  const isScheduleData = url.pathname.includes("/schedule-data/");
  const isDailyMessage = url.pathname.endsWith("/content/daily-messages.txt");
  if (isScheduleData || isDailyMessage) {
    event.respondWith((async () => {
      const cache = await caches.open(DATA_CACHE);
      try {
        const response = await fetch(request);
        if (response.ok) await cache.put(request, response.clone());
        return response;
      } catch {
        return (await cache.match(request)) || Response.error();
      }
    })());
    return;
  }

  if (request.mode === "navigate") {
    event.respondWith((async () => {
      const cache = await caches.open(SHELL_CACHE);
      try {
        const response = await fetch(request);
        if (response.ok) await cache.put(new URL("./index.html", self.location.href), response.clone());
        return response;
      } catch {
        return (await cache.match(request))
          || (await cache.match(new URL("./index.html", self.location.href)))
          || Response.error();
      }
    })());
    return;
  }

  if (shellPaths.has(url.pathname)) {
    event.respondWith((async () => {
      const cache = await caches.open(SHELL_CACHE);
      try {
        const response = await fetch(request);
        if (response.ok) await cache.put(request, response.clone());
        return response;
      } catch {
        return (await cache.match(request)) || Response.error();
      }
    })());
  }
});
