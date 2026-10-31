(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const message = $('msg');
  const result = $('result');
  let devices = [];
  let operation = null;

  $('origin').textContent = window.location.origin;

  function selectedDevice() {
    return devices.find((device) => device.deviceId === $('device').value);
  }

  function updateButtons() {
    document.querySelectorAll('button[data-action], button[data-control], #load, #claim, #revoke')
      .forEach((button) => {
        const needsDevice = button.id === 'revoke' || button.dataset.action || button.dataset.control;
        button.disabled = !!operation || (!!needsDevice && !selectedDevice());
      });
  }

  function cancel() {
    if (operation) operation.abort();
    operation = null;
    updateButtons();
  }

  function begin() {
    cancel();
    operation = new AbortController();
    updateButtons();
    return operation;
  }

  function finish(current) {
    if (operation !== current) return;
    operation = null;
    updateButtons();
  }

  async function api(path, options = {}, signal) {
    if (!path.startsWith('/api/')) throw new Error('invalid_request_path');
    const token = $('group').value.trim();
    const headers = { Accept: 'application/json' };
    if (token) headers.Authorization = `Bearer ${token}`;
    if (options.body !== undefined) headers['Content-Type'] = 'application/json';
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (signal?.aborted) throw new DOMException('cancelled', 'AbortError');
    signal?.addEventListener('abort', abort, { once: true });
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, 12000);
    try {
      const response = await fetch(path, {
        ...options, headers, credentials: 'omit', redirect: 'error', signal: controller.signal
      });
      let value;
      try { value = await response.json(); } catch (_) {
        if (controller.signal.aborted) throw new DOMException('cancelled', 'AbortError');
        throw new Error('invalid_server_response');
      }
      if (!response.ok) throw new Error(value.error || 'request_failed');
      return value;
    } catch (error) {
      if (timedOut) throw new Error('请求超时，请检查服务连接');
      throw error;
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', abort);
    }
  }

  function renderDevices(preferred = $('device').value) {
    $('device').replaceChildren();
    for (const device of devices) {
      const option = document.createElement('option');
      option.value = device.deviceId;
      option.textContent = `${device.name || device.deviceId} · ${device.online ? '在线' : '离线'}`;
      $('device').append(option);
    }
    if (!devices.length) $('device').append(new Option('没有已授权设备', ''));
    if (devices.some((device) => device.deviceId === preferred)) $('device').value = preferred;
    updateButtons();
  }

  async function refresh(current, preferred) {
    const response = await api('/api/devices', {}, current.signal);
    if (operation !== current) return;
    devices = response.devices || [];
    renderDevices(preferred);
  }

  async function loadDevices() {
    if (operation) return;
    const current = begin();
    try {
      await refresh(current);
      if (operation === current) message.textContent = `已连接 ${devices.length} 台设备`;
    } catch (error) { report(error, current); }
    finally { finish(current); }
  }

  async function claim() {
    if (operation) return;
    const code = $('code').value.trim();
    if (!/^\d{8}$/.test(code)) { message.textContent = '请输入8位数字配对码'; return; }
    const current = begin();
    try {
      const response = await api('/api/groups/claim', { method: 'POST', body: JSON.stringify({ code }) }, current.signal);
      if (operation !== current) return;
      $('group').value = response.groupToken || $('group').value;
      $('newToken').textContent = response.groupToken || '';
      $('tokenNotice').hidden = !response.groupToken;
      $('code').value = '';
      message.textContent = '配对成功。请复制并安全保存组令牌；刷新页面后需要重新输入。';
      await refresh(current, response.deviceId);
    } catch (error) { report(error, current); }
    finally { finish(current); }
  }

  function formatTime(value) {
    const seconds = Math.max(0, Math.floor((Number(value) || 0) / 1000));
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
  }

  function describe(command) {
    const labels = { queued: '排队中', delivered: '设备已收到', done: '已完成', failed: '设备执行失败', expired: '已过期', revoked: '已撤销' };
    const status = labels[command.status] || command.status;
    if (command.type === 'device.status' && command.status === 'done' && command.result) {
      const media = command.result;
      const states = { 1: '空闲', 2: '已暂停/就绪', 3: '播放中', 6: '缓冲中' };
      return `${media.title || '当前播放状态'}\n${states[media.state] || '未知状态'} · ${formatTime(media.position)} / ${formatTime(media.duration)}`;
    }
    return command.result ? `${status}\n${JSON.stringify(command.result, null, 2)}` : status;
  }

  function delay(signal) {
    return new Promise((resolve, reject) => {
      const abort = () => { clearTimeout(timer); reject(new DOMException('cancelled', 'AbortError')); };
      const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve(); }, 700);
      signal.addEventListener('abort', abort, { once: true });
      if (signal.aborted) abort();
    });
  }

  async function command(type, payload) {
    const device = selectedDevice();
    if (!device || operation) return;
    const current = begin();
    try {
      const response = await api('/api/commands', {
        method: 'POST', body: JSON.stringify({ targetDeviceId: device.deviceId, type, payload, idempotencyKey: crypto.randomUUID(), ttlSeconds: 30 })
      }, current.signal);
      if (operation !== current) return;
      result.textContent = '命令已发送，等待设备响应…';
      const deadline = Date.now() + 35000;
      while (Date.now() < deadline && operation === current) {
        const state = await api(`/api/commands/${encodeURIComponent(response.commandId)}`, {}, current.signal);
        if (operation !== current) return;
        result.textContent = describe(state.command);
        if (['done', 'failed', 'expired', 'revoked'].includes(state.command.status)) {
          message.textContent = state.command.status === 'done' ? '设备已返回结果' : describe(state.command).split('\n')[0];
          return;
        }
        await delay(current.signal);
      }
      if (operation === current) throw new Error('等待结果超时，请读取状态确认；不要重复发送切集命令');
    } catch (error) { report(error, current); }
    finally { finish(current); }
  }

  async function revoke() {
    const device = selectedDevice();
    if (!device || operation) return;
    if (!window.confirm(`确定撤销“${device.name || device.deviceId}”在当前组的授权吗？待执行命令也会撤销。`)) return;
    const current = begin();
    try {
      await api(`/api/devices/${encodeURIComponent(device.deviceId)}`, { method: 'DELETE' }, current.signal);
      if (operation !== current) return;
      devices = devices.filter((item) => item.deviceId !== device.deviceId);
      renderDevices();
      result.textContent = '';
      message.textContent = '设备授权已撤销';
      await refresh(current);
    } catch (error) { report(error, current); }
    finally { finish(current); }
  }

  function report(error, current) {
    if (operation === current && error.name !== 'AbortError') message.textContent = error.message;
  }

  $('claim').onclick = claim;
  $('load').onclick = loadDevices;
  $('revoke').onclick = revoke;
  $('device').onchange = () => { cancel(); result.textContent = ''; message.textContent = '已切换设备；取消等待不会撤销已发送的命令'; };
  $('group').oninput = () => { cancel(); devices = []; renderDevices(); result.textContent = ''; $('tokenNotice').hidden = true; $('newToken').textContent = ''; };
  $('copyToken').onclick = async () => {
    try { await navigator.clipboard.writeText($('newToken').textContent); message.textContent = '组令牌已复制，请保存到密码管理器。'; }
    catch (_) { message.textContent = '复制失败，请手动复制组令牌。'; }
  };
  $('dismissToken').onclick = () => { $('tokenNotice').hidden = true; $('newToken').textContent = ''; };
  document.addEventListener('visibilitychange', () => { if (document.hidden) cancel(); });
  document.querySelectorAll('[data-action]').forEach((button) => {
    button.onclick = () => {
      const action = button.dataset.action;
      if (action === 'status') return command('device.status', {});
      if (action === 'search') return command('action.search', { word: $('word').value });
      if (action === 'push') return command('action.push', { url: $('url').value });
    };
  });
  document.querySelectorAll('[data-control]').forEach((button) => {
    button.onclick = () => command('action.control', { action: button.dataset.control });
  });
  updateButtons();
})();
