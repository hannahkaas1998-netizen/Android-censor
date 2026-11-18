package com.example.screencensor

/**
 * Injected into old.reddit.com listing pages (front page, subreddits, user submissions).
 * It reads the same listing as JSON (with the login cookies of this browser) and replaces the page with a
 * plain phone feed: subreddit, title, picture. Nothing else. Images still go through ImageProxy.
 * Posts without a picture (text, links, videos) are skipped. Tap the title or picture to open the post.
 * NOTE: no dollar signs allowed in here (Kotlin raw string).
 */
object FeedMode {
    const val JS = """
(function () {
  if (window.__scFeed) return;
  var path = location.pathname;
  if (/\/(comments|wiki|login|register|prefs|message|submit|api|over18|settings|about)(\/|(?![\s\S]))/.test(path)) return;
  window.__scFeed = true;

  var base = location.origin + (path.charAt(path.length - 1) === '/' ? path : path + '/') + '.json';
  var qs = new URLSearchParams(location.search);
  qs.set('raw_json', '1');
  qs.set('limit', '25');
  var after = null, busy = false, done = false, started = false, chain = 0, seen = {};
  var root, list, sent, io, status;

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function choose(arr) {
    var need = Math.min(1080, window.innerWidth * (window.devicePixelRatio || 1));
    arr.sort(function (a, b) { return a.w - b.w; });
    for (var i = 0; i < arr.length; i++) if (arr[i].w >= need * 0.6) return arr[i];
    return arr[arr.length - 1];
  }

  function imgOf(p) {
    var a = [];
    if (p.is_gallery && p.gallery_data && p.gallery_data.items && p.media_metadata) {
      var m = p.media_metadata[p.gallery_data.items[0].media_id];
      if (m && m.status === 'valid') {
        (m.p || []).forEach(function (x) { a.push({ u: x.u, w: x.x, h: x.y }); });
        if (m.s && m.s.u) a.push({ u: m.s.u, w: m.s.x, h: m.s.y });
      }
    } else if (p.preview && p.preview.images && p.preview.images.length) {
      var im = p.preview.images[0];
      (im.resolutions || []).forEach(function (x) { a.push({ u: x.url, w: x.width, h: x.height }); });
      if (im.source) a.push({ u: im.source.url, w: im.source.width, h: im.source.height });
    }
    a = a.filter(function (x) { return x.u && x.w && x.h; });
    return a.length ? choose(a) : null;
  }

  function card(p) {
    var pic = imgOf(p);
    if (!pic) return null;
    var link = location.origin + p.permalink;
    var c = el('article', 'c');
    var s = el('a', 's', 'r/' + p.subreddit);
    s.href = location.origin + '/r/' + p.subreddit + '/';
    var t = el('a', 't', p.title);
    t.href = link;
    var a = el('a', 'i');
    a.href = link;
    var im = new Image();
    im.alt = '';
    im.loading = 'lazy';
    im.decoding = 'async';
    im.style.aspectRatio = pic.w + ' / ' + pic.h;
    im.src = pic.u;
    a.appendChild(im);
    c.appendChild(s);
    c.appendChild(t);
    c.appendChild(a);
    return c;
  }

  function setup() {
    var css =
      'html,body{margin:0!important;padding:0!important;background:#111!important}' +
      'body>*:not(#sc){display:none!important}' +
      '#sc{font-family:sans-serif;color:#eee;max-width:640px;margin:0 auto}' +
      '#sc .bar{position:sticky;top:0;z-index:5;background:#1b1b1b;padding:10px 12px;border-bottom:1px solid #333}' +
      '#sc .bar b{font-size:17px;margin-right:12px}' +
      '#sc .bar a{color:#9ab;font-size:14px;margin-right:12px;text-decoration:none}' +
      '#sc .c{margin:0 0 14px 0;background:#181818}' +
      '#sc .s{display:block;color:#8ab4f8;font-size:13px;padding:10px 12px 2px;text-decoration:none}' +
      '#sc .t{display:block;color:#fff;font-size:16px;font-weight:600;line-height:1.3;padding:2px 12px 10px;text-decoration:none;word-wrap:break-word}' +
      '#sc .i{display:block;line-height:0}' +
      '#sc img{display:block;width:100%;height:auto;background:#222}' +
      '#sc .m{padding:18px;text-align:center;color:#888;font-size:14px}' +
      '#sc button{background:#333;color:#fff;border:0;padding:10px 18px;border-radius:6px;font-size:15px}';
    var st = document.createElement('style');
    st.textContent = css;
    document.head.appendChild(st);
    if (!document.querySelector('meta[name=viewport]')) {
      var mv = document.createElement('meta');
      mv.name = 'viewport';
      mv.content = 'width=device-width,initial-scale=1';
      document.head.appendChild(mv);
    }
    root = el('div');
    root.id = 'sc';
    var bar = el('div', 'bar');
    var rm = path.match(/^\/r\/([^\/]+)/);
    var title = rm ? 'r/' + rm[1] : (path.indexOf('/user/') === 0 ? path.split('/').slice(1, 3).join('/') : 'Front page');
    bar.appendChild(el('b', null, title));
    var sm = path.match(/^(\/r\/[^\/]+)?(?:\/(hot|new|top|rising|controversial))?\/?(?![\s\S])/);
    if (sm) {
      ['hot', 'new', 'top', 'rising'].forEach(function (k) {
        var a = el('a', null, k);
        a.href = location.origin + (sm[1] || '') + '/' + k + '/';
        bar.appendChild(a);
      });
    }
    list = el('div');
    sent = el('div', 'm', '');
    status = sent;
    root.appendChild(bar);
    root.appendChild(list);
    root.appendChild(sent);
    document.body.appendChild(root);
    io = new IntersectionObserver(function (e) { if (e[0].isIntersecting) load(); }, { rootMargin: '1500px' });
    io.observe(sent);
  }

  function load() {
    if (busy || done) return;
    busy = true;
    var q = new URLSearchParams(qs.toString());
    if (after) q.set('after', after);
    fetch(base + '?' + q.toString(), { credentials: 'include', headers: { 'Accept': 'application/json' } })
      .then(function (r) { if (!r.ok) throw new Error('http'); return r.json(); })
      .then(function (d) {
        if (!d || d.kind !== 'Listing' || !d.data) throw new Error('nolisting');
        if (!started) { started = true; setup(); }
        var added = 0;
        (d.data.children || []).forEach(function (ch) {
          var p = ch.data;
          if (ch.kind !== 't3' || !p || p.stickied || p.is_video || seen[p.id]) return;
          seen[p.id] = 1;
          var c = card(p);
          if (c) { list.appendChild(c); added++; }
        });
        after = d.data.after;
        if (!after) { done = true; status.textContent = 'End of feed'; }
        busy = false;
        if (!done) {
          if (added === 0 && chain < 8) { chain++; load(); return; }
          chain = 0;
          io.unobserve(sent);
          io.observe(sent);
        }
      })
      .catch(function () {
        busy = false;
        if (!started) { window.__scFeed = false; return; }
        status.textContent = '';
        var b = el('button', null, 'Could not load - tap to retry');
        b.onclick = function () { status.textContent = ''; load(); };
        status.appendChild(b);
      });
  }
  load();
})();
"""
}
