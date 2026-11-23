/**
 * QQ 音乐扫码登录实现（浏览器版）
 *
 * 扫码链路本身不加密：ptqrshow 直接下发二维码图片与 qrsig，
 * ptqrlogin 用 hash33 算出的 ptqrtoken 轮询。HTTP 走 net:fetch（OkHttp）。
 */

import { bytesToBase64, getCookies, httpRequest, mergeSetCookie, setCookies } from "../net/http";

/** 网页 UA，QQ 登录态对 UA 敏感 */
const WEB_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
/** 移动端接口入口 */
const QM_API_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";
/** 移动端请求头 */
const QM_HEADERS: Record<string, string> = {
  "Content-Type": "application/json",
  "User-Agent": "QQMusic 14090008(android 15)",
  Referer: "https://y.qq.com",
};
/** 请求体 comm 字段，伪装安卓客户端 */
const commonParams = (): Record<string, string | number> => ({
  ct: 11,
  cv: 14090008,
  v: 14090008,
  chid: "10003505",
  os_ver: "15",
  phonetype: "24122RKC7C",
  tmeAppID: "qqmusic",
  nettype: "NETWORK_WIFI",
  udid: "0",
  OpenUDID: "0",
  QIMEI36: "0",
  uin: "0",
});

/**
 * QQ 系哈希，用于 ptqrtoken
 * @param str - 待哈希串
 * @param seed - 初始值
 * @returns 结果
 */
const hash33 = (str: string, seed = 0): number => {
  let h = seed;
  for (let i = 0; i < str.length; i++) {
    h = (((h << 5) + h + str.charCodeAt(i)) & 0xffffffff) >>> 0;
  }
  return h & 2147483647;
};

/**
 * 从响应头取出 Cookie
 * @param setCookie - Set-Cookie 头
 * @returns Cookie 键值对
 */
const parseCookies = (setCookie: string | undefined): Record<string, string> => mergeSetCookie({}, setCookie);

/**
 * 获取二维码（QQ 或微信）
 * @param type - qq 或 wx
 * @returns key 与 data URL
 */
const loginQrKey = async (type: string): Promise<any> => {
  if (type === "wx") {
    const searchParams = new URLSearchParams({
      appid: "wx48db31d50e334801",
      redirect_uri: "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/",
      response_type: "code",
      scope: "snsapi_login",
      state: "STATE",
      href: "https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect",
    });
    const page = await httpRequest({
      url: `https://open.weixin.qq.com/connect/qrconnect?${searchParams}`,
      headers: { "User-Agent": WEB_UA },
    });
    const match = /uuid=([^"]+)"|uuid=([a-zA-Z0-9_-]+)/.exec(page.text);
    const uuid = match?.[1] ?? match?.[2];
    if (!uuid) throw new Error("获取微信登录二维码 uuid 失败");
    const qr = await httpRequest({
      url: `https://open.weixin.qq.com/connect/qrcode/${uuid}`,
      headers: {
        Referer: "https://open.weixin.qq.com/connect/qrconnect",
        "User-Agent": WEB_UA,
      },
    });
    return {
      code: 200,
      key: uuid,
      content: `data:image/jpeg;base64,${bytesToBase64(qr.bytes)}`,
      type: "wx",
    };
  }

  const url = `https://ssl.ptlogin2.qq.com/ptqrshow?appid=716027609&e=2&l=M&s=3&d=72&v=4&t=${Math.random()}&daid=383&pt_3rd_aid=100497308`;
  const res = await httpRequest({
    url,
    headers: { Referer: "https://xui.ptlogin2.qq.com/", "User-Agent": WEB_UA },
  });
  const cookies = parseCookies(res.headers["set-cookie"]);
  const qrsig = cookies.qrsig;
  if (!qrsig) throw new Error("未能获取到 QQ 登录 qrsig");
  return {
    code: 200,
    key: qrsig,
    content: `data:image/png;base64,${bytesToBase64(res.bytes)}`,
    type: "qq",
  };
};

/**
 * 轮询扫码状态
 * @param key - qrsig
 * @returns 状态：0 过期 / 2 已扫待确认 / 4 成功
 */
const loginQrCheck = async (key: string): Promise<any> => {
  const query = new URLSearchParams({
    u1: "https://graph.qq.com/oauth2.0/login_jump",
    ptqrtoken: String(hash33(key, 0)),
    ptredirect: "0",
    h: "1",
    t: "1",
    g: "1",
    from_ui: "1",
    ptlang: "2052",
    action: `0-0-${Date.now()}`,
    js_ver: "20102616",
    js_type: "1",
    pt_uistyle: "40",
    aid: "716027609",
    daid: "383",
    pt_3rd_aid: "100497308",
    has_onekey: "1",
  });
  const res = await httpRequest({
    url: `https://ssl.ptlogin2.qq.com/ptqrlogin?${query}`,
    headers: {
      Referer: "https://xui.ptlogin2.qq.com/",
      Cookie: `qrsig=${key};`,
      "User-Agent": WEB_UA,
    },
  });
  const match = /ptuiCB\((.*?)\)/.exec(res.text);
  if (!match) return { code: 200, status: 1 };
  const args = [...match[1].matchAll(/'((?:\\.|[^'])*)'/g)].map((m) => m[1]);
  const statusCode = args[0];
  const nickname = args[5] || "";
  if (statusCode === "65") return { code: 200, status: 0 };
  if (statusCode === "67") return { code: 200, status: 2, nickname };
  if (statusCode === "0") {
    const jumpUrl = args[2] ?? "";
    if (jumpUrl.startsWith("http")) {
      // 手动跟随一次，取得 Set-Cookie 中的 p_skey，后续接口才有登录态
      const initial = mergeSetCookie({ qrsig: key }, res.headers["set-cookie"]);
      const check = await httpRequest({
        url: jumpUrl,
        headers: {
          Referer: "https://xui.ptlogin2.qq.com/",
          Cookie: Object.entries(initial)
            .map(([k, v]) => `${k}=${v}`)
            .join("; "),
          "User-Agent": WEB_UA,
        },
        followRedirects: false,
      });
      const session = mergeSetCookie(initial, check.headers["set-cookie"]);
      const pSkey = session.p_skey ?? session.skey ?? session.pskey;
      // uin 在跳转地址的查询串里，缺了它 user_detail 会判未登录
      let uin = "";
      try {
        uin = new URL(jumpUrl).searchParams.get("uin") ?? "";
      } catch {
        uin = "";
      }
      // QQ 的 uin 形如 o0123...，去掉前缀才是数字号
      const uinNum = uin.replace(/^o/, "");
      setCookies("qqmusic", {
        ...getCookies("qqmusic"),
        ...session,
        ...(pSkey ? { p_skey: pSkey } : {}),
        ...(uinNum ? { uin: uinNum } : {}),
        ...(nickname ? { nickname } : {}),
      });
      console.warn("[qq] login ok uin=" + uin + " hasPSkey=" + !!pSkey);
    }
    return { code: 200, status: 4, nickname };
  }
  return { code: 200, status: 1 };
};

/**
 * 调用 QQ 音乐接口
 * @param name - 接口名
 * @param params - 业务参数
 * @returns 统一响应
 */
export const qqmusicCall = async (
  name: string,
  params: Record<string, unknown> = {},
): Promise<{ ok: boolean; status?: number; body?: unknown; error?: string }> => {
  try {
    if (name === "login_qr_key") {
      return { ok: true, status: 200, body: await loginQrKey(String(params.type ?? "qq").toLowerCase()) };
    }
    if (name === "login_qr_check") {
      return { ok: true, status: 200, body: await loginQrCheck(String(params.key ?? "")) };
    }
    if (name === "user_detail" || name === "login_status") {
      const cookies = getCookies("qqmusic");
      console.warn("[qq] user_detail cookies=" + Object.keys(cookies).join(",").slice(0, 160));
      const uin = (cookies.uin ?? cookies.p_uin ?? "").replace(/^o/, "");
      if (!uin) return { ok: true, status: 200, body: { code: 200, loggedIn: false } };
      return {
        ok: true,
        status: 200,
        body: {
          code: 200,
          loggedIn: true,
          profile: { userId: uin, nickname: cookies.nickname ?? "" },
        },
      };
    }
    // 详情：专辑/歌手/歌单各有独立接口，不走通用透传
    if (name === "album" || name === "album_detail") {
      const mid = String(params.mid ?? params.albumMid ?? params.id ?? "");
      if (!mid) return { ok: false, error: "mid required" };
      return (await qqAlbumDetail(mid, String(params.name ?? ""))) as unknown as QmResponse;
    }
    if (name === "artist" || name === "artist_detail") {
      const mid = String(params.mid ?? params.singerMid ?? params.singermid ?? params.id ?? "");
      if (!mid) return { ok: false, error: "mid required" };
      return (await qqArtistDetail(mid, Number(params.offset ?? 0), Number(params.limit ?? 50))) as unknown as QmResponse;
    }
    if (name === "playlist" || name === "song_list" || name === "songlist") {
      const id = String(params.id ?? params.disstid ?? params.dissid ?? "");
      if (!id) return { ok: false, error: "id required" };
      return (await qqPlaylistDetail(id)) as unknown as QmResponse;
    }
    // 搜索：四类分类必须走签名网关，且专辑与其它分类用不同方法
    if (name === "search" || name === "search_song") {
      return (await qqSearch(params)) as unknown as QmResponse;
    }
    // 其余接口统一走 musicu 网关：一个 POST 就能覆盖搜索、详情、歌词等
    const session = await ensureSession();
    const body = {
      // 只放协议字段，内部缓存用的 expireAt 不能混进 comm
      comm: {
        ...commonParams(),
        ...(session.uid ? { uid: session.uid } : {}),
        ...(session.sid ? { sid: session.sid } : {}),
        ...(session.userip ? { userip: session.userip } : {}),
      },
      request: {
        module: String(params.module ?? ""),
        method: String(params.method ?? ""),
        param: params.param ?? {},
      },
    };
    const cookies = getCookies("qqmusic");
    const cookieStr = Object.entries(cookies)
      .map(([key, value]) => `${key}=${value}`)
      .join("; ");
    const res = await httpRequest({
      url: QM_API_URL,
      method: "POST",
      headers: {
        ...QM_HEADERS,
        Cookie: cookieStr || "tmeLoginType=-1;",
      },
      body: JSON.stringify(body),
      contentType: "application/json",
    });
    console.warn("[qq] hdr=" + JSON.stringify({ ...QM_HEADERS, Cookie: cookieStr || "tmeLoginType=-1;" }).slice(0, 300));
    if (res.status >= 400) return { ok: false, error: `HTTP ${res.status}`, status: res.status };
    return { ok: true, status: res.status, body: JSON.parse(res.text) };
  } catch (error) {
    return { ok: false, error: error instanceof Error ? error.message : String(error) };
  }
};

/** 匿名会话缓存，1 小时有效 */
let sessionCache: { uid?: string; sid?: string; userip?: string; expireAt: number } | null = null;

/**
 * 取 QQ 音乐的匿名会话
 *
 * 签名之外的接口都要求 comm 里带 uid/sid/userip，缺了会被判 2001
 * @returns 可直接展开进 comm 的会话字段
 */
const ensureSession = async (): Promise<{ uid?: string; sid?: string; userip?: string }> => {
  if (sessionCache && sessionCache.expireAt > Date.now()) return sessionCache;
  try {
    const res = await httpRequest({
      url: QM_API_URL,
      method: "POST",
      headers: { ...QM_HEADERS, Cookie: "tmeLoginType=-1;" },
      body: JSON.stringify({
        comm: commonParams(),
        request: {
          module: "music.getSession.session",
          method: "GetSession",
          param: { caller: 0, uid: "0", vkey: 0 },
        },
      }),
      contentType: "application/json",
    });
    const parsed = JSON.parse(res.text) as { request?: { data?: { session?: Record<string, string> } } };
    const info = parsed?.request?.data?.session ?? {};
    sessionCache = {
      uid: info.uid,
      sid: info.sid,
      userip: info.userip,
      expireAt: Date.now() + 3_600_000,
    };
  } catch {
    sessionCache = { expireAt: Date.now() + 60_000 };
  }
  return sessionCache;
};

/** zzc 签名常量表 */
const ZZC_CONST = [89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123, 120, 64, 242, 133, 143, 161, 121, 179];
const ZZC_PREFIX = [23, 14, 6, 36, 16, 7, 19];
const ZZC_SUFFIX = [16, 1, 32, 12, 19, 27, 8, 5];

/**
 * 计算 musics.fcg 的 zzc 签名
 * @param payload - 请求体 JSON 字符串
 * @returns 签名串
 */
const zzcSign = async (payload: string): Promise<string> => {
  const digest = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(payload));
  const hash = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
  const prefix = ZZC_PREFIX.map((i) => hash[i]).join("");
  const suffix = ZZC_SUFFIX.map((i) => hash[i]).join("");
  const bytes = ZZC_CONST.map((v, i) => v ^ parseInt(hash.slice(i * 2, i * 2 + 2), 16));
  const middle = btoa(String.fromCharCode(...bytes))
    .replace(/[/+=]/g, "");
  return (`zzc${prefix}${middle}${suffix}`).toLowerCase();
};

/** 搜索分类到 QQ search_type 的映射 */
const QQ_SEARCH_TYPE: Record<number, number> = { 0: 0, 8: 2, 9: 1, 2: 3 };

/**
 * 按分类搜索 QQ 音乐
 * @param params - keyword/page/limit/type
 * @returns 分类结果
 */
const qqSearch = async (params: Record<string, unknown>): Promise<any> => {
  const keyword = String(params.keywords ?? params.keyword ?? params.query ?? "");
  const page = Number(params.page ?? 1);
  const limit = Math.min(Number(params.limit ?? 30), 30);
  const type = Number(params.type ?? 0);
  const searchType = QQ_SEARCH_TYPE[type] ?? 0;
  // 专辑与歌手的 Lite 分支已被服务端整体判 2001，各自换可用的方法
  const method =
    type === 9
      ? "DoSearchForQQMusicMobile"
      : type === 8
        ? "DoSearchForQQMusicDesktop"
        : "DoSearchForQQMusicLite";
  const payload = JSON.stringify({
    comm: {
      ...commonParams(),
      cv: 1003006,
      v: 1003006,
      tmeAppID: "qqmusiclight",
    },
    req_1: {
      module: "music.search.SearchCgiService",
      method,
      param: {
        search_id: String(Date.now()) + Math.floor(Math.random() * 1000),
        remoteplace: "search.android.keyboard",
        query: keyword,
        page_num: page,
        num_per_page: limit,
        search_type: searchType,
        highlight: 0,
        nqc_flag: 0,
        page_id: 1,
        grp: 1,
      },
    },
  });
  const res = await httpRequest({
    url: `https://u.y.qq.com/cgi-bin/musics.fcg?sign=${await zzcSign(payload)}`,
    method: "POST",
    headers: { ...QM_HEADERS, Cookie: "tmeLoginType=-1;" },
    body: payload,
    contentType: "application/json",
  });
  const parsed = JSON.parse(res.text) as { req_1?: { code?: number; data?: unknown } };
  const node = parsed?.req_1;
  const raw = node?.data;
  const data = (typeof raw === "string" ? JSON.parse(raw) : raw) as {
    body?: Record<string, any>;
    meta?: { sum?: number };
  };
  const body = data?.body ?? {};
  const total = data?.meta?.sum ?? 0;
  console.warn("[qq] search type=" + type + " code=" + String(node?.code) + " total=" + total);
  if (node?.code !== 0) {
    console.warn("[qq] search rejected type=" + type + " code=" + String(node?.code) + " kw=" + keyword);
  }
  // 页面是 https，封面 http 会被 mixed content 拦掉；必须在分支之前声明，否则 TDZ
  const secure = (url: unknown): string | undefined =>
    typeof url === "string" && url ? url.replace(/^http:\/\//, "https://") : undefined;
  if (type === 8) {
    return { albums: (body.album?.list ?? []).map((a: any) => ({
      // 前端拿 id 去开详情页，而详情接口要的是 albumMID
      id: String(a.albumMID ?? a.albumMid ?? a.albumID ?? ""),
      name: a.albumName ?? a.name ?? "",
      artist: (a.singerName ?? a.singer ?? []).toString(),
      cover: secure(a.albumPic ?? a.pic),
      songCount: a.song_count ?? 0,
    })), total };
  }
  if (type === 9) {
    // Mobile 返回数组，Desktop 返回 { list }，两种形状都吃
    const singerList: any[] = Array.isArray(body.singer) ? body.singer : (body.singer?.list ?? []);
    return { artists: singerList.map((s: any) => ({
      id: String(s.singerMID ?? s.singerMid ?? s.mid ?? ""),
      name: s.singerName ?? s.name ?? "",
      cover: secure(s.singerPic ?? s.iconurl),
      songCount: s.songNum ?? 0,
    })), total };
  }
  if (type === 2) {
    return { playlists: (body.item_songlist ?? []).map((p: any) => ({
      id: String(p.dissid ?? p.id ?? ""),
      name: p.dissname ?? p.title ?? "",
      creator: p.nickname ?? p.creator?.name ?? "",
      cover: secure(p.logo),
      songCount: p.songnum ?? 0,
    })), total };
  }
  return { songs: (body.item_song ?? body.song?.list ?? []).map((s: any) => ({
    id: s.mid ?? String(s.id ?? ""),
    name: s.title ?? s.name ?? "",
    artist: (s.singer ?? []).map((a: any) => a.name).join(" / "),
    artists: (s.singer ?? []).map((a: any) => ({ name: a.name })),
    album: s.album?.name ?? "",
    cover: s.album?.mid
      ? `https://y.gtimg.cn/music/photo_new/T002R300x300M000${s.album.mid}.jpg`
      : undefined,
    duration: Number(s.interval ?? 0) * 1000,
    mid: s.mid,
    extId: String(s.id ?? ""),
  })), total };
};

/** 统一的 musicu 调用，供详情接口复用 */
const musicu = async (module: string, method: string, param: Record<string, unknown>): Promise<any> => {
  const session = await ensureSession();
  const res = await httpRequest({
    url: QM_API_URL,
    method: "POST",
    headers: { ...QM_HEADERS, Cookie: "tmeLoginType=-1;" },
    body: JSON.stringify({
      comm: {
        ...commonParams(),
        ...(session.uid ? { uid: session.uid } : {}),
        ...(session.sid ? { sid: session.sid } : {}),
        ...(session.userip ? { userip: session.userip } : {}),
      },
      req_1: { module, method, param },
    }),
    contentType: "application/json",
  });
  const parsed = JSON.parse(res.text) as { req_1?: { code?: number; data?: unknown } };
  const node = parsed?.req_1;
  const raw = node?.data;
  return { code: node?.code, data: typeof raw === "string" ? JSON.parse(raw) : raw };
};

/** 把 QQ 歌曲对象转成前端 Track 形状 */
const qqSongToTrack = (song: any): any => {
  const artistList = song.singer ?? [];
  return {
    id: song.mid ?? String(song.id ?? ""),
    extId: String(song.id ?? ""),
    name: song.name ?? song.title ?? "",
    artist: artistList.map((a: any) => a.name).join(" / "),
    artists: artistList.map((a: any) => ({ id: a.mid, name: a.name })),
    album: song.album?.name ?? "",
    cover: song.album?.mid
      ? `https://y.gtimg.cn/music/photo_new/T002R300x300M000${song.album.mid}.jpg`
      : undefined,
    duration: Number(song.interval ?? 0) * 1000,
    mid: song.mid,
  };
};

/**
 * QQ 专辑详情与曲目
 * @param mid - 专辑 mid
 * @param fallbackName - 兜底专辑名
 * @returns 详情
 */
const qqAlbumDetail = async (mid: string, fallbackName = ""): Promise<any> => {
  // 兼容前端可能传数字 albumID 的旧记录
  const isNumeric = /^[0-9]+$/.test(mid);
  const res = await musicu("music.musichallAlbum.AlbumSongList", "GetAlbumSongList", {
    albumMid: isNumeric ? "" : mid,
    albumID: isNumeric ? Number(mid) : 0,
    begin: 0,
    num: 999,
    order: 2,
  });
  const list: any[] = res?.data?.songList ?? [];
  const songs = list.map((item: any) => qqSongToTrack(item.songInfo ?? item));
  console.warn("[qq] album detail mid=" + mid + " n=" + songs.length);
  return {
    code: 200,
    id: mid,
    name: songs[0]?.album || fallbackName,
    cover: songs[0]?.cover,
    artist: songs[0]?.artist ?? "",
    total: res?.data?.totalNum ?? songs.length,
    songs,
  };
};

/**
 * QQ 歌手详情（歌曲 + 专辑）
 * @param mid - 歌手 mid
 * @param offset - 歌曲起始偏移
 * @param limit - 歌曲数量
 * @returns 详情
 */
const qqArtistDetail = async (mid: string, offset = 0, limit = 50): Promise<any> => {
  const [songsRes, albumsRes] = await Promise.all([
    musicu("musichall.song_list_server", "GetSingerSongList", {
      singerMid: mid,
      order: 1,
      begin: offset,
      num: limit,
    }),
    musicu("music.web_singer_info_svr", "get_singer_album", {
      singermid: mid,
      order: "time",
      begin: 0,
      num: 200,
      exstatus: 1,
    }),
  ]);
  const songs = (songsRes?.data?.songList ?? []).map((item: any) =>
    qqSongToTrack(item.songInfo ?? item),
  );
  const albums = (albumsRes?.data?.list ?? []).map((a: any) => ({
    id: a.album_mid ?? String(a.albumid ?? ""),
    name: a.album_name ?? "",
    artist: songs[0]?.artist ?? "",
    cover: a.album_mid
      ? `https://y.gtimg.cn/music/photo_new/T002R300x300M000${a.album_mid}.jpg`
      : undefined,
    publishTime: a.publish_time ?? "",
  }));
  console.warn("[qq] artist detail mid=" + mid + " songs=" + songs.length + " albums=" + albums.length);
  return {
    code: 200,
    artist: {
      id: mid,
      name: albumsRes?.data?.singer_name ?? songs[0]?.artist ?? "",
      songCount: songsRes?.data?.totalNum ?? songs.length,
      albumCount: albumsRes?.data?.total ?? albums.length,
    },
    songs,
    albums,
  };
};

/**
 * QQ 歌单详情
 * @param disstid - 歌单 id
 * @returns 详情
 */
const qqPlaylistDetail = async (disstid: string): Promise<any> => {
  const url = `https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysonglist=0&platform=yqq&needNewCode=0&disstid=${encodeURIComponent(disstid)}`;
  const res = await httpRequest({
    url,
    headers: { ...QM_HEADERS, Referer: "https://y.qq.com/" },
  });
  // 响应可能被 jsonCallback(...) 包裹，用切片剥离避免正则转义问题
  let text = res.text.trim();
  if (text.startsWith("jsonCallback")) text = text.slice("jsonCallback".length).trim();
  if (text.startsWith("(")) text = text.slice(1);
  while (text.endsWith(";") || text.endsWith(")")) text = text.slice(0, -1).trimEnd();
  const parsed = JSON.parse(text) as { cdlist?: any[] };
  const cd = parsed?.cdlist?.[0] ?? {};
  const songs = (cd.songlist ?? []).map((s: any) => ({
    id: s.songmid ?? String(s.songid ?? ""),
    extId: String(s.songid ?? ""),
    name: s.songname ?? "",
    artist: (s.singer ?? []).map((a: any) => a.name).join(" / "),
    artists: (s.singer ?? []).map((a: any) => ({ id: a.mid, name: a.name })),
    album: s.albumname ?? "",
    cover: s.albummid
      ? `https://y.gtimg.cn/music/photo_new/T002R300x300M000${s.albummid}.jpg`
      : undefined,
    duration: Number(s.interval ?? 0) * 1000,
    mid: s.songmid,
  }));
  console.warn("[qq] playlist detail id=" + disstid + " n=" + songs.length);
  return {
    code: 200,
    id: disstid,
    name: cd.dissname ?? "",
    creator: cd.nickname ?? "",
    description: cd.desc ?? "",
    cover: cd.logo ? cd.logo.replace(/^http:\/\//, "https://") : undefined,
    total: cd.songnum ?? songs.length,
    songs,
  };
};

/**
 * 清空 QQ 音乐登录态
 */
export const clearQQMusicSession = (): void => {
  setCookies("qqmusic", {});
};

/**
 * 手动写入 QQ 音乐 Cookie
 * @param cookie - Cookie 字符串
 */
export const setQQMusicCookie = (cookie: string): void => {
  const current = getCookies("qqmusic");
  for (const part of cookie.split(";")) {
    const eq = part.indexOf("=");
    if (eq <= 0) continue;
    current[part.slice(0, eq).trim()] = part.slice(eq + 1).trim();
  }
  setCookies("qqmusic", current);
};
