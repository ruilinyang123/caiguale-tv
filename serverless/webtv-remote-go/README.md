# WebTV 公网遥控服务（Go）

首期仅提供 `action.search`、`action.push`、`action.control`（play/pause/stop/prev/next/repeat/loop/replay）和 `device.status`。不提供文件、安装、登录态、代理、配置同步等能力。

## 本地运行

```sh
go run . -listen 127.0.0.1:8787 -state data/identities.json -origin http://127.0.0.1:8787
```

生产必须保持 Go 仅 loopback 监听，由 nginx 终止 HTTPS 并代理 WebSocket。身份文件由服务原子写入，权限为 0600；命令和绑定码仅内存保存，重启后不会重放。

## Android 接口契约（v1）

时间字段为 epoch milliseconds。HTTP JSON 请求体最大 16 KiB，响应错误统一为 `{ok:false,error:"stable_code"}`。设备请求使用 `Authorization: Bearer <deviceToken>` 和 `X-Device-Id`；首次注册响应中的身份只显示一次，客户端应安全保存。组管理请求使用 `Authorization: Bearer <groupToken>`。

- `POST /api/device/register` `{name,appVersion?}` → `deviceId,deviceToken,groupIds,server`；带现有身份则仅更新元数据。
- `POST /api/device/bind-code` 设备鉴权 → 一次性八位数字码，300 秒有效。每设备同时只有一个有效码。
- `POST /api/groups/claim` `{code}` → 创建组并返回 `groupId,groupToken,deviceId`；也可带已有组 token 将设备加入该组。
- `GET /api/devices` 组鉴权 → 设备列表。
- `POST /api/device/poll` 设备鉴权 → `{command:null|Command,groupIds}`，建议 2 秒轮询。
- `GET /api/device/ws`：升级后 10 秒内发送 `{type:"hello",deviceId,deviceToken}`；服务端发送 `{type:"ready",ok,deviceId,groupIds}` 和 `{type:"command",command}`；设备发送 `{type:"result",commandId,ok,result}`，服务端响应 `{type:"ack",commandId,ok:true}`。标准 ping/pong，每 30 秒 ping；失败回退 poll。
- `POST /api/commands` 组鉴权：`{targetDeviceId,type,payload,idempotencyKey,ttlSeconds?}`。TTL 默认 30、最大 120 秒；返回 `commandId,command`。服务端首次投递后状态变为 `delivered`，不会重复投递；Android 仍应按 id 去重并检查 `expiresAt`。

命令 payload 精确格式（其他字段一律拒绝）：

| type | payload |
| --- | --- |
| `action.search` | `{ "word": "片名" }`，非空，最多 200 个 Unicode 字符 |
| `action.push` | `{ "url": "https://example.com/video.mp4" }`，最多 4096 字节，仅 HTTP(S)，不接受 URL 凭据或 fragment |
| `action.control` | `{ "action": "play" }`，仅 play/pause/stop/prev/next/repeat/loop/replay |
| `device.status` | `{}`，设备 result 返回 Media 快照 |

Command 格式为 `{id,groupId,targetDeviceId,type,payload,status,createdAt,expiresAt,result?,finishedAt?}`。status 为 queued/delivered/done/failed/expired/revoked。结果请求体为 `{ok:boolean,result:object}`。相同组的相同 `idempotencyKey`（8–128 字节）在命令创建后十分钟内返回同一命令，内容改变则 409；过期键或服务重启后不得复用旧操作键。

交付语义是 **至多一次投递，不保证动作执行**：WS 写出或 HTTP 响应丢失时不会重投以避免重复播放操作，必须由用户确认状态后决定新操作。撤销不能召回已在网络上或设备上执行的动作；设备必须检查有效期并在本地关闭遥控时停止执行。组 token 永远不下发给被控设备；设备不得自行上报或恢复组授权。
- `GET /api/commands/{id}` 组鉴权，`POST /api/commands/{id}/result` 设备鉴权。
- `DELETE /api/devices/{deviceId}` 组鉴权撤销本组授权；`POST /api/device/revoke` 撤销设备全部授权；`POST /api/device/groups/{groupId}/revoke` 撤销单组。

## nginx

复制 `deploy/nginx.conf`，将 `remote.example.com` 替换为实际域名和证书路径。必须覆盖 `X-Real-IP`，并转发 `Upgrade` / `Connection`。

## systemd

复制 `deploy/webtv-remote.service`，修改 `User`、二进制路径和状态目录后安装。服务账户只应拥有状态目录权限。例如由管理员手动执行（本次交付不自动部署）：

```sh
sudo useradd --system --home /var/lib/webtv-remote --shell /usr/sbin/nologin webtv-remote
sudo install -m 0755 dist/webtv-remote-linux-amd64 /usr/local/bin/webtv-remote-go
sudo install -m 0644 deploy/webtv-remote.service /etc/systemd/system/webtv-remote.service
# 修改服务中的 -origin，与 HTTPS 域名严格一致，并配置 nginx 证书。
sudo systemctl daemon-reload
sudo systemctl enable --now webtv-remote
sudo nginx -t && sudo systemctl reload nginx
```

`StateDirectory` 自动创建 0700 状态目录。身份文件包含 token 的 SHA-256 摘要和授权关系，不保存明文 token；请备份并保护文件，损坏时服务拒绝启动而不是静默重建身份。Linux 下原子 rename + 文件 fsync + 目录 fsync；只运行一个服务实例，不支持多个进程共享状态文件。Windows 本地开发的 chmod 不代表 POSIX 0600，生产部署使用 Linux，Windows 请另行限制目录 ACL。

反代必须覆盖而不是追加 `X-Real-IP`。只有显式 `-trusted-proxy 127.0.0.1/32` 的连接才信任此头；不配置时按 TCP 对端限流，反代后所有设备会共享限额。绑定生成每设备每分钟 5 次，claim 每 IP 每分钟 10 次且全局每分钟 120 次；注册每 IP 每小时 10 次，API 每 IP 每分钟 600 次。大型站点需部署边缘限流，不能仅依靠内存限流对抗分布式滥用。

网页仅同源访问；令牌不使用 cookie，不接受 URL query 传递凭据，不开放 CORS。请勿将组 token 粘贴到非本服务页面或开启请求体日志。没有 token 找回功能，丢失 token 后需在设备上重新生成绑定码，撤销不再使用的旧组。

## 验证与构建

```sh
go test -timeout 30s ./...
go test -race -timeout 30s ./...  # 需要 cgo 和 C 编译器
go vet ./...
mkdir -p dist
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -o dist/webtv-remote-linux-amd64 .
GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -o dist/webtv-remote-linux-arm64 .
```

`dist/` 与 `data/` 已忽略。Web 文件以 embed 编入二进制，修改网页后需重建并重启服务。
