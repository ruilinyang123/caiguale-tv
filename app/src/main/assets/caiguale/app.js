/* 菜瓜乐影视 · 本地 WebHome 首页/搜索/详情
 * 数据全部来自 window.fm.invoke('vod.*')（原生 HomeWebBridge / CaigualeVodBridge）；
 * 页面不含演示影片、虚构评分或自造的播放入口。
 * 兼容 Android TV 旧 WebView：不使用 ?. / ?? / :focus-visible / flex gap。
 */
(function () {
  'use strict';

  var state = {
    route: 'home',
    sites: [],
    siteKey: '',
    siteName: '',
    sitesStatus: 'idle',
    homeSeq: 0,
    homeStatus: 'idle',
    homeError: null,
    types: [],
    items: [],
    searchSeq: 0,
    searchStatus: 'idle',
    searchError: null,
    searchKeyword: '',
    searchResults: [],
    detail: null,
    flags: [],
    detailSeq: 0,
    detailError: null,
    fromIndex: 0,
    episode: null,
    focusMemory: {},
    lastModalFocus: null,
    toastTimer: 0
  };

  /* ---------------- 基础工具 ---------------- */
  function $(id) { return document.getElementById(id); }
  function el(tag, cls, value) {
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (value !== undefined && value !== null) node.textContent = String(value);
    return node;
  }
  function clear(node) { while (node && node.firstChild) node.removeChild(node.firstChild); }
  function text(value) { return value === undefined || value === null ? '' : String(value).trim(); }
  function plain(value) {
    var raw = text(value);
    if (!raw) return '';
    var doc = null;
    try { doc = new DOMParser().parseFromString(raw, 'text/html'); } catch (e) { doc = null; }
    if (doc && doc.body) return text(doc.body.textContent).replace(/\s+/g, ' ');
    return raw.replace(/<[^>]*>/g, '').replace(/\s+/g, ' ');
  }
  function has(node) { return !!node && node.getClientRects().length > 0; }
  function toast(message, ms) {
    var node = $('toast');
    if (!node) return;
    node.textContent = message;
    node.className = 'toast show';
    if (state.toastTimer) clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(function () { node.className = 'toast'; }, ms || 2600);
  }

  /* ---------------- 原生 SDK（fm.invoke） ---------------- */
  // HomeWebController can inject the SDK and the per-load main-frame token after
  // the page's own scripts run. Never send an unauthenticated bootstrap request.
  // Keep this bounded: a broken bridge must not strand the home page forever.
  var BRIDGE_POLL_MS = 200;
  var BRIDGE_MAX_POLLS = 30;

  function bridgeInvoker() {
    if (window.fm && typeof window.fm.invoke === 'function') return { fn: window.fm.invoke, owner: window.fm };
    if (window.fongmi && typeof window.fongmi.invoke === 'function') return { fn: window.fongmi.invoke, owner: window.fongmi };
    return null;
  }

  function waitForBridgeReady() {
    return new Promise(function (resolve, reject) {
      var polls = 0;
      var timer = null;
      var completed = false;
      function cleanup() {
        if (timer) clearTimeout(timer);
        window.removeEventListener('fmsdk', check);
        window.removeEventListener('fmbridge-ready', check);
      }
      function check() {
        if (completed) return;
        var token = window.__fmBridgeToken;
        var invoker = bridgeInvoker();
        if (typeof token === 'string' && token.length > 0 && invoker) {
          completed = true;
          cleanup();
          resolve(invoker);
          return;
        }
        if (++polls >= BRIDGE_MAX_POLLS) {
          completed = true;
          cleanup();
          reject({ code: 'BRIDGE_TIMEOUT', message: '原生桥接初始化超时' });
          return;
        }
        if (timer) clearTimeout(timer);
        timer = setTimeout(check, BRIDGE_POLL_MS);
      }
      window.addEventListener('fmsdk', check);
      window.addEventListener('fmbridge-ready', check);
      check();
    });
  }

  function sdkInvoke(method, payload) {
    return waitForBridgeReady().then(function (invoker) {
      return new Promise(function (resolve, reject) {
        var out;
        try { out = invoker.fn.call(invoker.owner, method, payload || {}); } catch (e) {
          reject({ code: 'INVOKE_FAILED', message: '数据接口调用失败' });
          return;
        }
        Promise.resolve(out).then(function (value) {
          var data = value;
          if (typeof data === 'string') {
            try { data = JSON.parse(data); } catch (e) {
              reject({ code: 'INTERNAL', message: '原生返回内容无法解析' });
              return;
            }
          }
          if (data && data.ok === false) { reject(normalizeError(data.error)); return; }
          if (data === undefined || data === null) { reject({ code: 'INTERNAL', message: '原生返回空结果' }); return; }
          resolve(data);
        }, function (error) {
          reject(normalizeError(error));
        });
      });
    });
  }

  function normalizeError(error) {
    if (!error) return { code: 'INTERNAL', message: '未知错误' };
    if (typeof error === 'string') {
      var code = /未配置|config|empty|空/i.test(error) ? 'NO_CONFIG' : 'INTERNAL';
      return { code: code, message: error };
    }
    return { code: text(error.code).toUpperCase() || 'INTERNAL', message: text(error.message) };
  }

  function describeError(error) {
    var code = error && error.code ? error.code : 'INTERNAL';
    if (code === 'NO_CONFIG') return '尚未导入可用的片源配置。导入后即可在此浏览、搜索与播放。';
    if (code === 'NO_BRIDGE' || code === 'BRIDGE_TIMEOUT') return '正在等待原生接口连接，请稍后重试或重新打开首页。';
    if (code === 'NETWORK') return '无法连接到当前来源，请检查网络或稍后重试。';
    if (code === 'TIMEOUT') return '来源响应超时，请重试或在来源中更换站点。';
    return '读取内容失败，请重试，或换一个来源。';
  }

  /* ---------------- 数据模型 ---------------- */
  function normalizeVod(raw, fallbackKey, fallbackName) {
    if (!raw || typeof raw !== 'object') return null;
    var id = text(raw.id || raw.vod_id);
    var name = text(raw.name || raw.vod_name);
    if (!id || !name) return null;
    return {
      id: id,
      name: name,
      pic: text(raw.pic || raw.vod_pic),
      remarks: text(raw.remarks || raw.vod_remarks),
      year: text(raw.year || raw.vod_year),
      area: text(raw.area || raw.vod_area),
      typeName: text(raw.typeName || raw.type_name),
      content: plain(raw.content || raw.vod_content),
      siteKey: text(raw.siteKey || raw.site_key) || fallbackKey,
      siteName: text(raw.siteName || raw.site_name) || fallbackName
    };
  }

  function normalizeVods(list, fallbackKey, fallbackName) {
    var out = [];
    var seen = {};
    if (!Array.isArray(list)) return out;
    for (var i = 0; i < list.length; i++) {
      var raw = list[i];
      if (raw && raw.Vod && typeof raw.Vod === 'object') raw = raw.Vod;
      var item = normalizeVod(raw, fallbackKey, fallbackName);
      if (!item) continue;
      var key = item.siteKey + '|' + item.id;
      if (seen[key]) continue;
      seen[key] = true;
      out.push(item);
    }
    return out;
  }

  function siteLabel(site) { return text(site.name) || text(site.key); }

  /* ---------------- 原生动作 ---------------- */
  function playCurrent() {
    var item = state.route === 'detail' ? state.detail : state.items[0];
    if (!item) return;
    var mark = state.route === 'detail' && state.episode ? state.episode.name : '';
    var flagName = state.route === 'detail' && state.episode ? state.episode.flagName : '';
    sdkInvoke('vod.play', {
      siteKey: item.siteKey,
      vodId: item.id,
      title: item.name,
      pic: item.pic,
      mark: mark,
      flag: flagName
    }).then(function (result) {
      if (result && result.started === false) {
        showNotice('未能开始播放', '原生播放器没有接受这次请求，请在设置中检查来源是否仍然可用。');
        return;
      }
      toast(mark ? '正在播放：' + item.name + ' · ' + mark : '正在打开原生播放：' + item.name);
    }).catch(function (error) {
      showNotice('无法打开播放', describeError(error));
    });
  }

  function nativeSearch(keyword) {
    var search = window.fm && typeof window.fm.search === 'function' ? window.fm.search : null;
    if (!search) { showNotice('搜索需要原生客户端', '当前环境没有可用的原生搜索接口。'); return; }
    try { search(keyword, { direct: false }); } catch (e) { toast('原生搜索调用失败'); }
  }

  function openSetting() {
    var setting = window.fm && typeof window.fm.openSetting === 'function' ? window.fm.openSetting : null;
    if (!setting) { showNotice('设置需要原生客户端', '请在菜瓜乐影视 TV 客户端内打开设置。'); return; }
    try { setting(); } catch (e) { toast('打开设置失败'); }
  }

  /* ---------------- 路由与页面 ---------------- */
  function routeTo(route) {
    if (route !== 'search' && route !== 'detail') route = 'home';
    if (route === state.route) return;
    var active = document.activeElement;
    if (active && active.getAttribute && active.getAttribute('data-fkey')) {
      state.focusMemory[state.route] = active.getAttribute('data-fkey');
    }
    state.route = route;
    try { history.pushState({ route: route }, '', '#' + route); } catch (e) {}
    applyRoute();
  }

  function applyRoute() {
    $('pageHome').className = 'page' + (state.route === 'home' ? ' active' : '');
    $('pageSearch').className = 'page' + (state.route === 'search' ? ' active' : '');
    $('pageDetail').className = 'page' + (state.route === 'detail' ? ' active' : '');
    var navKey = state.route === 'search' ? 'search' : 'home';
    var links = document.querySelectorAll('[data-nav]');
    for (var i = 0; i < links.length; i++) {
      links[i].className = 'focusable navlink' + (links[i].getAttribute('data-nav') === navKey ? ' selected' : '');
    }
    restoreFocus();
  }

  function pageScope() {
    if (state.route === 'search') return $('pageSearch');
    if (state.route === 'detail') return $('pageDetail');
    return $('pageHome');
  }

  function cssKey(value) { return String(value).replace(/["\\]/g, '\\$&'); }

  function restoreFocus() {
    var key = state.focusMemory[state.route];
    var target = key ? document.querySelector('[data-fkey="' + cssKey(key) + '"]') : null;
    if (has(target)) { focusNode(target); return; }
    var list = focusables(pageScope());
    if (list.length) focusNode(list[0]);
  }

  window.addEventListener('popstate', function (event) {
    var route = event.state && event.state.route ? event.state.route : 'home';
    state.route = route === 'search' || route === 'detail' ? route : 'home';
    if (state.route === 'search') renderSearch();
    if (state.route === 'detail') renderDetail();
    applyRoute();
  });

  /* 供原生入口可选调用；返回 true 表示页面已消费这次返回。 */
  window.caigualeBack = function () {
    if ($('modal').className.indexOf('visible') >= 0) { closeModal(); return true; }
    var input = $('searchInput');
    if (state.route === 'search' && document.activeElement === input && input.readOnly === false) {
      input.readOnly = true;
      focusNode($('searchGo'));
      return true;
    }
    if (state.route !== 'home') { history.back(); return true; }
    return false;
  };

  /* ---------------- 焦点 ---------------- */
  function focusables(scope) {
    var list = (scope || document).querySelectorAll('.focusable');
    var out = [];
    for (var i = 0; i < list.length; i++) if (has(list[i]) && !list[i].disabled) out.push(list[i]);
    return out;
  }

  function focusNode(node) {
    if (!node) return;
    try { node.focus({ preventScroll: true }); } catch (e) { try { node.focus(); } catch (err) {} }
    try { node.scrollIntoView({ block: 'nearest', inline: 'nearest' }); } catch (e2) {}
  }

  function moveFocus(key) {
    var modal = $('modal');
    var scope = modal.className.indexOf('visible') >= 0 ? modal : pageScope();
    var all = focusables(scope);
    if (!all.length) return;
    var active = document.activeElement;
    if (all.indexOf(active) < 0) { focusNode(all[0]); return; }
    var rect = active.getBoundingClientRect();
    var cx = (rect.left + rect.right) / 2;
    var cy = (rect.top + rect.bottom) / 2;
    var horizontal = key === 'ArrowLeft' || key === 'ArrowRight';
    var best = null;
    var bestScore = Infinity;
    for (var i = 0; i < all.length; i++) {
      var node = all[i];
      if (node === active) continue;
      var r = node.getBoundingClientRect();
      var dx = (r.left + r.right) / 2 - cx;
      var dy = (r.top + r.bottom) / 2 - cy;
      var primary = key === 'ArrowRight' ? dx : key === 'ArrowLeft' ? -dx : key === 'ArrowDown' ? dy : -dy;
      if (primary < 8) continue;
      var cross = horizontal ? Math.abs(dy) : Math.abs(dx);
      var score = primary + cross * (horizontal ? 2.4 : 3.2);
      if (score < bestScore) { bestScore = score; best = node; }
    }
    if (best) focusNode(best);
  }

  document.addEventListener('focusin', function (event) {
    var node = event.target;
    if (!node || !node.getBoundingClientRect) return;
    if (node.className && String(node.className).indexOf('movie') >= 0) {
      try { node.scrollIntoView({ block: 'nearest', inline: 'nearest' }); } catch (e) {}
    }
  });

  document.addEventListener('keydown', function (event) {
    var key = event.key;
    var modalOpen = $('modal').className.indexOf('visible') >= 0;
    if (key === 'Escape' || key === 'Backspace' || key === 'BrowserBack' || key === 'GoBack') {
      var editing = document.activeElement === $('searchInput') && $('searchInput').readOnly === false;
      if (key === 'Backspace' && editing) return;
      event.preventDefault();
      if (modalOpen) { closeModal(); return; }
      if (editing) { $('searchInput').readOnly = true; focusNode($('searchGo')); return; }
      if (state.route !== 'home') { history.back(); return; }
      if (window.fm && typeof window.fm.back === 'function') { try { window.fm.back(); } catch (e) {} }
      return;
    }
    if (key === 'ArrowUp' || key === 'ArrowDown' || key === 'ArrowLeft' || key === 'ArrowRight') {
      var input = $('searchInput');
      if (document.activeElement === input && input.readOnly === false) {
        if (key === 'ArrowLeft' || key === 'ArrowRight') return;
      }
      event.preventDefault();
      moveFocus(key);
      return;
    }
    if (key === 'Enter') {
      var active = document.activeElement;
      if (active === $('searchInput') && active.readOnly) {
        event.preventDefault();
        active.readOnly = false;
        active.focus();
        toast('输入片名后按 OK 开始搜索');
        return;
      }
      if (active === $('searchInput')) { event.preventDefault(); doSearch(); return; }
    }
  });

  document.addEventListener('click', function (event) {
    var target = event.target;
    var actNode = closest(target, '[data-act]');
    if (actNode) { handleAction(actNode.getAttribute('data-act')); return; }
    var nav = closest(target, '[data-nav]');
    if (nav) { handleNav(nav.getAttribute('data-nav')); return; }
    var card = closest(target, '[data-card]');
    if (card) { openDetail(card.getAttribute('data-card'), card.getAttribute('data-sk')); return; }
    if (target === $('searchInput') && target.readOnly) target.readOnly = false;
  });

  function closest(node, selector) {
    while (node && node !== document) {
      if (node.matches && node.matches(selector)) return node;
      node = node.parentNode;
    }
    return null;
  }

  function findItem(siteKey, id) {
    var pools = [state.items, state.searchResults];
    for (var p = 0; p < pools.length; p++) {
      for (var i = 0; i < pools[p].length; i++) {
        if (pools[p][i].id === id && (!siteKey || pools[p][i].siteKey === siteKey)) return pools[p][i];
      }
    }
    return null;
  }

  /* ---------------- 启动加载 ---------------- */
  function preloadSites() {
    state.sitesStatus = 'loading';
    renderHome();
    sdkInvoke('vod.sites', {}).then(function (data) {
      var list = Array.isArray(data) ? data : (Array.isArray(data.sites) ? data.sites : []);
      state.sites = list.filter(function (site) { return site && text(site.key); });
      state.sitesStatus = 'ready';
      if (!state.sites.length) {
        state.homeStatus = 'ready';
        state.items = [];
        renderHome();
        return;
      }
      var exists = false;
      for (var i = 0; i < state.sites.length; i++) if (text(state.sites[i].key) === state.siteKey) exists = true;
      if (!exists) { state.siteKey = text(state.sites[0].key); state.siteName = siteLabel(state.sites[0]); }
      updateSiteLabel();
      loadHome(state.siteKey);
    }).catch(function (error) {
      state.sitesStatus = 'error';
      state.homeStatus = 'error';
      state.homeError = normalizeError(error);
      renderHome();
    });
  }

  function loadHome(siteKey) {
    var seqId = ++state.homeSeq;
    state.homeStatus = 'loading';
    state.homeError = null;
    renderHome();
    sdkInvoke('vod.home', { siteKey: siteKey || '' }).then(function (data) {
      if (seqId !== state.homeSeq) return;
      state.items = normalizeVods(data.list, text(data.siteKey) || siteKey, text(data.siteName) || state.siteName);
      state.types = Array.isArray(data['class']) ? data['class'] : [];
      if (data.siteKey) state.siteKey = text(data.siteKey);
      if (data.siteName) state.siteName = text(data.siteName);
      state.homeStatus = 'ready';
      updateSiteLabel();
      renderHome();
    }).catch(function (error) {
      if (seqId !== state.homeSeq) return;
      state.homeError = normalizeError(error);
      state.homeStatus = 'error';
      renderHome();
    });
  }

  function updateSiteLabel() {
    $('siteName').textContent = state.siteName || '';
    $('sourceLink').textContent = state.siteName ? '来源 · ' + state.siteName : '来源';
  }

  /* ---------------- 首页渲染 ---------------- */
  function renderHome() {
    var hero = $('hero');
    var rails = $('rails');
    clear(rails);
    hero.className = 'hero';
    setBackdrop($('heroImg'), $('heroFallback'), '', '');

    if (state.sitesStatus === 'loading' || state.sitesStatus === 'idle' || state.homeStatus === 'loading' || state.homeStatus === 'idle') {
      hero.style.display = 'none';
      var loading = el('div', 'skeleton-row');
      loading.appendChild(el('div', 'sk sk-hero'));
      loading.appendChild(el('div', 'sk sk-line'));
      var row = el('div', 'cards');
      for (var i = 0; i < 5; i++) row.appendChild(el('div', 'sk sk-card'));
      loading.appendChild(row);
      rails.appendChild(loading);
      return;
    }
    hero.style.display = '';

    if (state.sitesStatus === 'error' || state.homeStatus === 'error') {
      hero.style.display = 'none';
      showHomeState(state.homeError || { code: 'INTERNAL' });
      return;
    }
    if (!state.sites.length) {
      hero.style.display = 'none';
      showHomeState({ code: 'NO_CONFIG' });
      return;
    }
    if (!state.items.length) {
      hero.style.display = 'none';
      showHomeState({ code: 'EMPTY' });
      return;
    }

    var featured = state.items[0];
    $('heroEyebrow').textContent = '来自 ' + (featured.siteName || state.siteName || '当前来源');
    $('heroTitle').textContent = featured.name;
    var meta = $('heroMeta');
    clear(meta);
    addMeta(meta, featured.year);
    addMeta(meta, featured.typeName);
    addMeta(meta, featured.area);
    addMetaPill(meta, featured.remarks);
    var synopsis = $('heroSynopsis');
    synopsis.textContent = featured.content || '';
    synopsis.style.display = featured.content ? '' : 'none';
    setBackdrop($('heroImg'), $('heroFallback'), featured.pic, featured.name);

    groupRails().forEach(function (group) { rails.appendChild(buildRail(group)); });
  }

  function setBackdrop(img, fallback, url, name) {
    if (!img || !fallback) return;
    fallback.style.display = 'none';
    fallback.textContent = '';
    img.className = 'heroimg';
    img.style.display = url ? '' : 'none';
    if (!url) { img.removeAttribute('src'); return; }
    img.onerror = function () {
      img.style.display = 'none';
      fallback.style.display = 'flex';
      fallback.textContent = (name || '菜').slice(0, 2);
    };
    img.onload = function () { img.className = 'heroimg ready'; };
    img.src = url;
  }

  function showHomeState(error) {
    var rails = $('rails');
    clear(rails);
    var panel = el('div', 'statepanel');
    var box = el('div', 'statebox');
    var code = error && error.code ? error.code : 'INTERNAL';
    var title = '暂时无法读取内容';
    var body = describeError(error);
    if (code === 'NO_CONFIG' || code === 'NO_SITES') {
      title = '还没有配置内容源';
      body = '菜瓜乐影视不内置片源。请先在设置中导入你自己的订阅配置，导入后这里会直接显示该来源的真实片单。';
    } else if (code === 'EMPTY') {
      title = '当前来源暂无内容';
      body = '该来源首页返回了空列表。可以换一个来源，或使用原生搜索查找内容。';
    } else if (code === 'NO_BRIDGE') {
      title = '当前版本不支持本地首页数据';
      body = '未检测到原生数据接口。可以改用原生搜索与设置，或更新客户端。';
    }
    box.appendChild(el('h2', '', title));
    box.appendChild(el('p', '', body));
    var controls = el('div', 'controls');
    var settings = el('button', 'focusable main', '去设置导入配置源');
    settings.setAttribute('data-act', 'open-setting');
    settings.setAttribute('data-fkey', 'home-state-settings');
    controls.appendChild(settings);
    var retry = el('button', 'focusable', '重试');
    retry.setAttribute('data-act', 'retry-home');
    retry.setAttribute('data-fkey', 'home-state-retry');
    controls.appendChild(retry);
    var nativeBtn = el('button', 'focusable', '原生搜索');
    nativeBtn.setAttribute('data-act', 'native-search-open');
    nativeBtn.setAttribute('data-fkey', 'home-state-native');
    controls.appendChild(nativeBtn);
    box.appendChild(controls);
    if (state.sites.length) {
      box.appendChild(el('p', 'codes', '已配置来源：' + state.sites.map(siteLabel).join(' / ')));
    }
    panel.appendChild(box);
    rails.appendChild(panel);
    $('heroTitle').textContent = '菜瓜乐影视';
    $('heroEyebrow').textContent = '本地首页';
    $('heroSynopsis').textContent = '';
    $('heroSynopsis').style.display = 'none';
    clear($('heroMeta'));
  }

  function addMeta(parent, value) {
    var txt = text(value);
    if (txt) parent.appendChild(el('span', '', txt));
  }
  function addMetaPill(parent, value) {
    var txt = text(value);
    if (txt) parent.appendChild(el('span', 'pill', txt));
  }

  function groupRails() {
    var groups = [];
    var map = {};
    for (var i = 0; i < state.items.length; i++) {
      var item = state.items[i];
      var key = item.typeName || '';
      if (!map[key]) { map[key] = { title: key || '当前来源内容', items: [] }; groups.push(map[key]); }
      map[key].items.push(item);
    }
    return groups;
  }

  function buildRail(group) {
    var rail = el('div', 'rail');
    var title = el('h2', '', group.title);
    title.appendChild(el('small', '', '共 ' + group.items.length + ' 部'));
    rail.appendChild(title);
    var cards = el('div', 'cards');
    for (var i = 0; i < group.items.length; i++) {
      var item = group.items[i];
      cards.appendChild(buildCard(item, 'card-' + item.siteKey + '-' + item.id));
    }
    rail.appendChild(cards);
    return rail;
  }

  function buildCard(item, key) {
    var card = el('button', 'focusable movie');
    card.setAttribute('data-card', item.id);
    card.setAttribute('data-sk', item.siteKey);
    card.setAttribute('data-fkey', key);
    card.setAttribute('aria-label', '查看 ' + item.name + ' 详情');
    var thumb = el('div', 'thumb');
    var fallback = el('div', 'fallback', item.name.slice(0, 2));
    thumb.appendChild(fallback);
    if (item.pic) {
      var img = el('img', 'poster');
      img.alt = '';
      img.loading = 'lazy';
      img.onerror = function () {
        img.style.display = 'none';
        fallback.style.display = 'flex';
      };
      img.onload = function () { fallback.style.display = 'none'; };
      img.src = item.pic;
      thumb.appendChild(img);
    }
    if (item.remarks) thumb.appendChild(el('span', 'flag', item.remarks));
    thumb.appendChild(el('span', 'overname', item.name));
    card.appendChild(thumb);
    var meta = el('div', 'movemeta');
    meta.appendChild(el('b', '', item.name));
    meta.appendChild(el('small', '', item.year || item.typeName || ''));
    card.appendChild(meta);
    return card;
  }

  /* ---------------- 弹层 ---------------- */
  function showModal(options) {
    var modal = $('modal');
    state.lastModalFocus = document.activeElement;
    $('modalTitle').textContent = options.title || '';
    var body = $('modalBody');
    clear(body);
    if (options.body) body.textContent = options.body;
    if (options.content) body.appendChild(options.content);
    var actions = $('modalActions');
    clear(actions);
    var list = options.actions || [{ label: '知道了', main: true }];
    for (var i = 0; i < list.length; i++) {
      (function (action, index) {
        var btn = el('button', 'focusable' + (action.main ? ' main' : ''), action.label);
        btn.setAttribute('data-fkey', 'modal-' + index);
        btn.addEventListener('click', function () {
          if (typeof action.onClick === 'function') action.onClick();
          closeModal();
        });
        actions.appendChild(btn);
      })(list[i], i);
    }
    modal.className = 'modalshade visible';
    modal.setAttribute('aria-hidden', 'false');
    var first = focusables(modal)[0];
    if (first) focusNode(first);
  }

  function closeModal() {
    var modal = $('modal');
    if (modal.className.indexOf('visible') < 0) return;
    modal.className = 'modalshade';
    modal.setAttribute('aria-hidden', 'true');
    var back = state.lastModalFocus;
    state.lastModalFocus = null;
    if (has(back)) focusNode(back);
    else restoreFocus();
  }

  function showNotice(title, body) {
    showModal({ title: title, body: body, actions: [{ label: '知道了', main: true }] });
  }

  function showSources() {
    var list = el('div', 'sitelist');
    if (!state.sites.length) {
      list.appendChild(el('p', '', '没有可选来源。请先在设置中导入你自己的订阅配置。'));
    }
    state.sites.forEach(function (site, index) {
      var btn = el('button', 'focusable' + (text(site.key) === state.siteKey ? ' selected' : ''), siteLabel(site));
      btn.setAttribute('data-fkey', 'site-' + index);
      btn.addEventListener('click', function () {
        closeModal();
        state.siteKey = text(site.key);
        state.siteName = siteLabel(site);
        state.items = [];
        updateSiteLabel();
        loadHome(state.siteKey);
      });
      list.appendChild(btn);
    });
    showModal({
      title: '选择来源',
      body: '',
      content: list,
      actions: [
        { label: '打开原生设置', onClick: openSetting },
        { label: '关闭', main: true }
      ]
    });
  }

  function handleNav(name) {
    if (name === 'search') {
      routeTo('search');
      state.focusMemory.search = 'search-input';
      renderSearch();
      focusNode($('searchInput'));
      return;
    }
    if (name === 'source') { showSources(); return; }
    if (name === 'settings') { openSetting(); return; }
    routeTo('home');
  }

  function handleAction(action) {
    if (action === 'back') { if (state.route !== 'home') history.back(); return; }
    if (action === 'play-hero' || action === 'play-detail') { playCurrent(); return; }
    if (action === 'detail-hero') { if (state.items[0]) openDetail(state.items[0].id, state.items[0].siteKey); return; }
    if (action === 'open-setting') { openSetting(); return; }
    if (action === 'retry-home') { state.siteKey ? loadHome(state.siteKey) : preloadSites(); return; }
    if (action === 'native-search-open') {
      showModal({
        title: '使用原生搜索',
        body: '将离开此页面，进入菜瓜乐影视的原生搜索。返回后仍会回到这里。',
        actions: [
          { label: '进入原生搜索', main: true, onClick: function () { nativeSearch(state.searchKeyword); } },
          { label: '取消' }
        ]
      });
      return;
    }
    if (action === 'do-search') { doSearch(); return; }
    if (action === 'sources') { scrollToEpisodes(); return; }
    if (action === 'search-title') {
      if (!state.detail) return;
      state.searchKeyword = state.detail.name;
      routeTo('search');
      renderSearch();
      doSearch();
      return;
    }
    if (action === 'native-fallback-search') {
      showModal({
        title: '使用原生搜索',
        body: '本地搜索未返回结果时，可以直接使用客户端的原生搜索。',
        actions: [
          { label: '原生搜索', main: true, onClick: function () { nativeSearch(state.searchKeyword); } },
          { label: '取消' }
        ]
      });
    }
  }

  /* ---------------- 搜索 ---------------- */
  function renderSearch() {
    var input = $('searchInput');
    if (document.activeElement !== input) input.value = state.searchKeyword || input.value;
    $('searchHint').textContent = state.siteName ? '仅搜索当前来源：' + state.siteName : '仅搜索当前来源；结果来自已配置站点。';
    var body = $('searchBody');
    clear(body);
    if (state.searchStatus === 'idle') {
      var tip = el('div', 'statebox');
      tip.appendChild(el('h2', '', '按片名搜索'));
      tip.appendChild(el('p', '', '搜索通过原生接口执行，只覆盖已配置来源。'));
      body.appendChild(tip);
      return;
    }
    if (state.searchStatus === 'loading') {
      var row = el('div', 'resultgrid');
      for (var i = 0; i < 6; i++) row.appendChild(el('div', 'sk sk-card'));
      body.appendChild(row);
      return;
    }
    if (state.searchStatus === 'error') {
      var box = el('div', 'statebox');
      box.appendChild(el('h2', '', '搜索失败'));
      box.appendChild(el('p', '', describeError(state.searchError)));
      var controls = el('div', 'controls');
      var retry = el('button', 'focusable main', '重试');
      retry.setAttribute('data-act', 'do-search');
      retry.setAttribute('data-fkey', 'search-retry');
      controls.appendChild(retry);
      var nativeBtn = el('button', 'focusable', '原生搜索');
      nativeBtn.setAttribute('data-act', 'native-fallback-search');
      nativeBtn.setAttribute('data-fkey', 'search-native');
      controls.appendChild(nativeBtn);
      box.appendChild(controls);
      body.appendChild(box);
      return;
    }
    if (!state.searchResults.length) {
      var empty = el('div', 'statebox');
      empty.appendChild(el('h2', '', '没有找到结果'));
      empty.appendChild(el('p', '', '当前来源没有返回与「' + state.searchKeyword + '」匹配的内容。可以更换关键词、切换来源，或使用原生搜索。'));
      var row2 = el('div', 'controls');
      var native2 = el('button', 'focusable', '尝试原生搜索');
      native2.setAttribute('data-act', 'native-fallback-search');
      native2.setAttribute('data-fkey', 'search-native-empty');
      row2.appendChild(native2);
      empty.appendChild(row2);
      body.appendChild(empty);
      return;
    }
    body.appendChild(el('p', 'hint', '在 ' + (state.siteName || '当前来源') + ' 找到 ' + state.searchResults.length + ' 个结果'));
    var grid = el('div', 'resultgrid');
    state.searchResults.forEach(function (item) {
      grid.appendChild(buildCard(item, 'search-' + item.siteKey + '-' + item.id));
    });
    body.appendChild(grid);
  }

  function doSearch() {
    var input = $('searchInput');
    var keyword = text(input.value);
    if (!keyword) { toast('请输入要搜索的片名'); focusNode(input); return; }
    state.searchKeyword = keyword;
    state.searchStatus = 'loading';
    state.searchError = null;
    renderSearch();
    var seqId = ++state.searchSeq;
    sdkInvoke('vod.search', { keyword: keyword, page: 1, siteKey: state.siteKey }).then(function (data) {
      if (seqId !== state.searchSeq) return;
      var list = Array.isArray(data) ? data : (Array.isArray(data.list) ? data.list : []);
      state.searchResults = normalizeVods(list, state.siteKey, state.siteName);
      state.searchStatus = 'ready';
      renderSearch();
      var first = document.querySelector('[data-fkey^="search-"]');
      if (first) focusNode(first); else focusNode($('searchGo'));
    }).catch(function (error) {
      if (seqId !== state.searchSeq) return;
      state.searchError = normalizeError(error);
      state.searchStatus = 'error';
      renderSearch();
    });
  }

  /* ---------------- 详情 ---------------- */
  function openDetail(id, siteKey) {
    var item = findItem(siteKey || state.siteKey, id);
    if (!item) { toast('条目已不在当前列表'); return; }
    state.detail = item;
    state.flags = [];
    state.detailError = null;
    state.fromIndex = 0;
    state.episode = null;
    state.detailSeq++;
    $('pageDetail').scrollTop = 0;
    routeTo('detail');
    state.focusMemory.detail = 'detail-play';
    renderDetail();
    focusNode(document.querySelector('[data-fkey="detail-play"]'));
    refreshDetail(item);
  }

  function refreshDetail(item) {
    var seqId = ++state.detailSeq;
    sdkInvoke('vod.detail', { siteKey: item.siteKey, vodId: item.id }).then(function (data) {
      if (seqId !== state.detailSeq) return;
      var raw = data;
      if (Array.isArray(data.list) && data.list.length) raw = data.list[0];
      var fresh = normalizeVod(raw, item.siteKey, item.siteName);
      if (!fresh || fresh.id !== item.id) return;
      fresh.pic = fresh.pic || item.pic;
      fresh.siteKey = fresh.siteKey || item.siteKey;
      fresh.siteName = fresh.siteName || item.siteName;
      state.detail = fresh;
      state.flags = parseFlags(data.flags);
      state.detailError = null;
      renderDetail();
    }).catch(function (error) {
      if (seqId !== state.detailSeq) return;
      state.detailError = normalizeError(error);
      renderDetail();
    });
  }

  function parseFlags(flags) {
    var out = [];
    if (!Array.isArray(flags)) return out;
    for (var i = 0; i < flags.length; i++) {
      var flag = flags[i];
      if (!flag) continue;
      var eps = [];
      var rawEps = Array.isArray(flag.episodes) ? flag.episodes : [];
      for (var j = 0; j < rawEps.length; j++) {
        var ep = rawEps[j];
        if (!ep) continue;
        var name = text(ep.name) || ('第 ' + (j + 1) + ' 集');
        eps.push({ name: name, url: text(ep.url) });
      }
      if (!eps.length) continue;
      out.push({ name: text(flag.flag) || ('线路 ' + (i + 1)), episodes: eps });
    }
    return out;
  }

  function renderDetail() {
    var item = state.detail;
    if (!item) return;
    $('detailSource').textContent = '来自 ' + (item.siteName || state.siteName || '当前来源');
    $('detailTitle').textContent = item.name;
    var meta = $('detailMeta');
    clear(meta);
    addMeta(meta, item.year);
    addMeta(meta, item.typeName);
    addMeta(meta, item.area);
    addMetaPill(meta, item.remarks);
    var synopsis = $('detailSynopsis');
    synopsis.textContent = item.content || '';
    synopsis.style.display = item.content ? '' : 'none';
    $('detailCredits').textContent = '来源　' + (item.siteName || state.siteName || '当前站点');
    var note = $('detailNote');
    if (state.detailError) {
      note.hidden = false;
      note.textContent = '详情补充信息读取失败。当前展示列表中已有的真实字段，播放由原生处理。';
    } else {
      note.hidden = true;
      note.textContent = '';
    }
    setBackdrop($('detailBanner'), $('detailFallback'), item.pic, item.name);
    var playBtn = document.querySelector('[data-act="play-detail"]');
    if (playBtn) playBtn.setAttribute('aria-label', '播放 ' + item.name);
    renderEpisodes(item);
  }

  function renderEpisodes(item) {
    var section = $('episodes');
    if (!state.flags.length) { section.hidden = true; return; }
    section.hidden = false;
    if (state.fromIndex >= state.flags.length) state.fromIndex = 0;
    var fromRow = $('fromRow');
    clear(fromRow);
    for (var i = 0; i < state.flags.length; i++) {
      (function (index) {
        var btn = el('button', 'focusable' + (index === state.fromIndex ? ' selected' : ''), state.flags[index].name);
        btn.setAttribute('data-fkey', 'from-' + index);
        btn.addEventListener('click', function () {
          state.fromIndex = index;
          state.episode = null;
          renderEpisodes(item);
          focusNode(document.querySelector('[data-fkey="from-' + index + '"]'));
        });
        fromRow.appendChild(btn);
      })(i);
    }
    var active = state.flags[state.fromIndex];
    $('episodeCount').textContent = active.episodes.length + ' 集 · ' + active.name;
    var grid = $('epGrid');
    clear(grid);
    for (var j = 0; j < active.episodes.length; j++) {
      (function (index) {
        var ep = active.episodes[index];
        var selected = state.episode && state.episode.from === state.fromIndex && state.episode.index === index;
        var btn = el('button', 'focusable' + (selected ? ' selected' : ''), ep.name);
        btn.setAttribute('data-fkey', 'ep-' + state.fromIndex + '-' + index);
        btn.setAttribute('aria-label', '选择 ' + active.name + ' 的 ' + ep.name);
        btn.addEventListener('click', function () {
          state.episode = { from: state.fromIndex, index: index, name: ep.name, flagName: active.name };
          renderEpisodes(item);
          focusNode(document.querySelector('[data-fkey="ep-' + state.fromIndex + '-' + index + '"]'));
          toast('已选择 ' + active.name + ' · ' + ep.name + '，按播放从这一集开始');
        });
        grid.appendChild(btn);
      })(j);
    }
    $('epHint').textContent = state.episode
      ? '已选择 ' + state.episode.flagName + ' · ' + state.episode.name + '。按「播放」会把这一集的集名交给原生播放器。'
      : '线路与集名来自该来源的真实详情。未选集时按「播放」由原生按站点默认线路起播；选择具体集后会传集名。';
  }

  function scrollToEpisodes() {
    var section = $('episodes');
    if (section.hidden) {
      showNotice('没有可用的选集信息', '当前来源没有返回线路或剧集数据。播放会直接打开原生播放器，由客户端按来源规则处理。');
      return;
    }
    try { section.scrollIntoView({ block: 'start' }); } catch (e) {}
    var first = document.querySelector('[data-fkey="from-0"]') || document.querySelector('[data-fkey="ep-0-0"]');
    if (first) focusNode(first);
  }

  /* ---------------- 启动 ---------------- */
  function boot() {
    var navs = document.querySelectorAll('[data-nav]');
    for (var i = 0; i < navs.length; i++) navs[i].setAttribute('data-fkey', 'nav-' + navs[i].getAttribute('data-nav'));
    document.querySelector('[data-act="play-hero"]').setAttribute('data-fkey', 'hero-play');
    document.querySelector('[data-act="detail-hero"]').setAttribute('data-fkey', 'hero-detail');
    $('searchInput').setAttribute('data-fkey', 'search-input');
    $('searchGo').setAttribute('data-fkey', 'search-go');
    document.querySelector('[data-act="play-detail"]').setAttribute('data-fkey', 'detail-play');

    try { history.replaceState({ route: 'home' }, '', location.href.split('#')[0] + '#home'); } catch (e) {}
    applyRoute();
    updateSiteLabel();
    preloadSites();

    window.addEventListener('fmsdk', function () {
      if (state.sitesStatus === 'error') preloadSites();
    });
    window.addEventListener('fmresume', function () {
      var key = state.focusMemory[state.route];
      var node = key ? document.querySelector('[data-fkey="' + cssKey(key) + '"]') : null;
      if (has(node)) focusNode(node);
    });
    window.addEventListener('focus', function () {
      if (!has(document.activeElement)) restoreFocus();
    });
  }

  boot();
})();
