/**
 * 酷狗音乐接口实现（浏览器版）
 *
 * 上游走 Android 网关签名 + 设备注册，这里只保留能直接用的部分：
 * 二维码登录与公开 CDN 搜索。签名算法与上游一致（MD5(salt + 排序参数 + salt)）。
 */

import { getCookies, cookieHeader, httpJson, httpRequest, mergeSetCookie, setCookies } from "../net/http";
import { md5 } from "./md5";
import { fetchKugouLyric } from "./kugou-lyric";

/** 登录服务域名 */
const LOGIN_HOST = "https://login-user.kugou.com";
/** 公开搜索 CDN */
// 保持 https；该域名的证书是 *.cdn.myqcloud.com 泛域名，由 NetClient 放宽主机名校验
const CDN_HOST = "https://mobilecdn.kugou.com";
/** Web 签名盐 */
const WEB_SIGN_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";
/** 应用标识 */
const APPID = 1005;
/** 客户端版本 */
const CLIENTVER = 20489;
/** 扫码来源应用 */
const SRC_APPID = 2919;

/**
 * 设备号：上游按固定种子算出并缓存，这里等价处理
 * @returns 32 位十六进制设备号
 */
const deviceMid = (): string => md5("splayer-next-kugou-device-mid");

/**
 * Web 参数签名
 * @param params - 待签名参数
 * @returns 32 位十六进制签名
 */
const signWeb = (params: Record<string, unknown>): string => {
  const body = Object.keys(params)
    .map((key) => `${key}=${params[key] ?? ""}`)
    .sort()
    .join("");
  return md5(`${WEB_SIGN_SALT}${body}${WEB_SIGN_SALT}`);
};

/**
 * 带签名地请求登录服务
 * @param path - 相对路径
 * @param input - 业务参数
 * @returns 响应 JSON
 */
const loginRequest = async (path: string, input: Record<string, unknown>): Promise<any> => {
  const params: Record<string, unknown> = {
    dfid: "-",
    mid: deviceMid(),
    uuid: "-",
    appid: 1001,
    clientver: CLIENTVER,
    clienttime: Math.floor(Date.now() / 1000),
    ...input,
  };
  params.signature = signWeb(params);
  const query = new URLSearchParams(
    Object.entries(params).map(([key, value]) => [key, String(value)]),
  );
  const res = await httpJson<any>({ url: `${LOGIN_HOST}${path}?${query}` });
  return res;
};

/**
 * 获取扫码登录二维码
 * @returns key 与二维码内容
 */
const loginQrKey = async (): Promise<{ code: number; key: string; content: string }> => {
  const body = await loginRequest("/v2/qrcode", {
    type: 1,
    plat: 4,
    qrcode_txt: `https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=${APPID}&`,
    srcappid: SRC_APPID,
  });
  const key = body?.data?.qrcode;
  if (!key) throw new Error("KG QR key missing");
  // 接口本身就带二维码 PNG，直接给前端用，省掉再画一次
  const image = body?.data?.qrcode_img;
  return {
    code: 200,
    key: String(key),
    content: typeof image === "string" && image.startsWith("data:")
      ? image
      : `https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=${key}`,
  };
};

/**
 * 轮询扫码状态
 * @param key - 二维码 key
 * @returns 状态与资料
 */
const loginQrCheck = async (key: string): Promise<any> => {
  if (!key) throw new Error("KG QR key missing");
  const body = await loginRequest("/v2/get_userinfo_qrcode", {
    plat: 4,
    appid: APPID,
    srcappid: SRC_APPID,
    qrcode: key,
  });
  const data = body?.data ?? {};
  const nickname = data.username ?? data.nickname ?? data.nick_name ?? data.user_name;
  const avatarUrl =
    data.userpic ?? data.user_pic ?? data.avatar ?? data.avatar_url ?? data.pic ?? data.user_img;
  if (Number(data.status) === 4) {
    const cookies = getCookies("kugou");
    setCookies("kugou", {
      ...cookies,
      token: String(data.token ?? ""),
      userid: String(data.userid ?? ""),
      vip_token: String(data.vip_token ?? ""),
      vip_type: String(data.vip_type ?? ""),
    });
  }
  return {
    code: 200,
    status: Number(data.status ?? 0),
    nickname,
    avatarUrl,
    token: data.token,
    userid: data.userid,
  };
};

/** 酷狗 Android 网关：专辑/歌手/歌单只有它能搜到 */
const KG_GATEWAY = "https://gateway.kugou.com";
const KG_APPID = 1005;
const KG_CLIENTVER = 20489;
const ANDROID_SIGN_SALT = "OIlwieks28dk2k092lksi2UIkp";

/** 设备 MID：md5 结果按十六进制转十进制 */
const KG_MID = (() => {
  try {
    return BigInt("0x" + md5("splayer-next-kugou-device-mid")).toString(10);
  } catch {
    return "0";
  }
})();

/**
 * Android 版请求签名
 * @param params - 参与签名的参数
 * @returns 32 位签名
 */
const signatureAndroid = (params: Record<string, unknown>): string => {
  const body = Object.keys(params)
    .sort()
    .map((key) => {
      const value = params[key];
      const text = typeof value === "object" ? JSON.stringify(value) : (value ?? "");
      return `${key}=${String(text)}`;
    })
    .join("");
  return md5(`${ANDROID_SIGN_SALT}${body}${ANDROID_SIGN_SALT}`);
};

/**
 * 调用酷狗 Android 网关
 * @param path - 相对路径
 * @param params - 业务参数
 * @returns 网关响应
 */
const kgGateway = async (
  path: string,
  params: Record<string, unknown>,
  extra: { method?: "GET" | "POST"; data?: unknown; router?: string; base?: string } = {},
): Promise<any> => {
  const merged: Record<string, unknown> = {
    dfid: "-",
    mid: KG_MID,
    uuid: "-",
    appid: KG_APPID,
    clientver: KG_CLIENTVER,
    clienttime: Math.floor(Date.now() / 1000),
    ...params,
  };
  // POST 时签名要带上序列化后的请求体
  const bodyText = extra.data === undefined ? "" : JSON.stringify(extra.data);
  merged.signature = md5(
    `${ANDROID_SIGN_SALT}${Object.keys(merged).sort().map((key) => {
      const value = merged[key];
      return `${key}=${String(typeof value === "object" ? JSON.stringify(value) : (value ?? ""))}`;
    }).join("")}${bodyText}${ANDROID_SIGN_SALT}`,
  );
  const query = Object.entries(merged)
    .filter(([, value]) => value !== undefined && value !== null)
    .map(([key, value]) => `${key}=${encodeURIComponent(typeof value === "object" ? JSON.stringify(value) : String(value))}`)
    .join("&");
  return httpJson<any>({
    url: `${extra.base ?? KG_GATEWAY}${path}?${query}`,
    method: extra.method ?? "GET",
    headers: {
      Referer: "https://www.kugou.com/",
      // 歌单接口不能带 x-router，传空串表示不设该头
      ...(extra.router === "" ? {} : { "x-router": extra.router ?? "complexsearch.kugou.com" }),
      "kg-tid": "255",
    },
    ...(extra.data === undefined
      ? {}
      : { body: bodyText, contentType: "application/json" }),
  });
};

/**
 * 搜索歌曲
 * @param params - 关键词与分页
 * @returns 统一响应
 */
const search = async (params: Record<string, unknown>): Promise<any> => {
  const keyword = String(params.keywords ?? params.keyword ?? params.q ?? "");
  const page = Number(params.page ?? 1);
  const pagesize = Math.min(Number(params.limit ?? params.pagesize ?? 30), 30);
  const type = Number(params.type ?? 0);
  /** 酷狗封面模板里的 {size} 换成实际边长 */
  const cover = (raw: unknown): string | undefined =>
    typeof raw === "string" && raw
      ? raw.replace(/^http:\/\//, "https://").replace(/\{size\}/g, "300")
      : undefined;

  // 专辑 / 歌手 / 歌单只有 Android 网关能搜到，走签名请求
  if (type === 8 || type === 9 || type === 2) {
    const seg = type === 8 ? "album" : type === 9 ? "author" : "special";
    const res = await kgGateway(`/v1/search/${seg}`, {
      keyword,
      page,
      pagesize,
      platform: "AndroidFilter",
      iscorrection: 1,
    });
    const list: any[] = res?.data?.lists ?? res?.data?.info ?? [];
    console.warn("[kg] gw " + seg + " n=" + list.length);
    if (type === 8) {
      return {
        albums: list.map((item) => ({
          id: String(item.albumid ?? ""),
          name: item.albumname ?? "",
          artist: item.singer ?? item.singers?.map((s: any) => s.name).join(" / ") ?? "",
          cover: cover(item.img ?? item.imgurl),
          songCount: item.songcount ?? 0,
        })),
        total: res?.data?.total ?? list.length,
      };
    }
    if (type === 9) {
      return {
        artists: list.map((item) => ({
          id: String(item.AuthorId ?? ""),
          name: item.AuthorName ?? "",
          cover: cover(item.Avatar ?? item.FirstFrameImage),
          albumCount: item.AlbumCount ?? 0,
          songCount: item.AudioCount ?? 0,
        })),
        total: res?.data?.total ?? list.length,
      };
    }
    return {
      playlists: list.map((item) => ({
        id: String(item.gid ?? item.specialid ?? ""),
        name: item.specialname ?? "",
        creator: item.nickname ?? "",
        cover: cover(item.img ?? item.imgurl),
        songCount: item.song_count ?? item.songcount ?? 0,
      })),
      total: res?.data?.total ?? list.length,
    };
  }

  // 单曲改走 song_search_v2：只有它带封面 Image 字段
  const url = `https://songsearch.kugou.com/song_search_v2?keyword=${encodeURIComponent(keyword)}&page=${page}&pagesize=${pagesize}&platform=WebFilter`;
  // 必须显式带头：NetClient 默认会补网易的 Referer 与浏览器 UA，酷狗收到会返回空
  const body = await httpJson<any>({
    url,
    headers: {
      // 只带 Referer，UA 交给 NetClient 补浏览器值（酷狗不校验客户端 UA）
      Referer: "https://www.kugou.com/",
    },
  });
  const items: any[] = body?.data?.lists ?? [];
  console.warn("[kg] song n=" + items.length);
  // 直接返回业务结构：dispatch 的兜底会把它包成 { ok, data }
  return {
    total: body?.data?.total ?? items.length,
    songs: items.map((item) => ({
        id: String(item.FileHash ?? "").toLowerCase(),
        name: item.SongName ?? "",
        artist: item.SingerName ?? "",
        // 前端 kgSongToTrack 读 song.artists.map，缺了会抛 reading 'map'
        artists: [{ name: item.SingerName ?? "" }],
        album: item.AlbumName ?? "",
        albumName: item.AlbumName ?? "",
        cover: cover(item.Image),
        duration: Number(item.Duration ?? 0) * 1000,
        hash: String(item.FileHash ?? "").toLowerCase(),
        albumId: item.album_id,
        album_id: item.album_id,
        audio_id: item.audio_id,
        privilege: item.privilege,
        pay: item.pay ?? {},
      })),
  };
};

/**
 * 分页拉全（酷狗详情接口都是全量）
 * @param fetchPage - 单页取数函数
 * @param pageSize - 每页条数
 * @returns 合并结果
 */
const fetchAllPages = async <T>(
  fetchPage: (page: number) => Promise<{ items: T[]; total: number }>,
  pageSize: number,
): Promise<{ items: T[]; total: number }> => {
  const first = await fetchPage(1);
  const items = [...first.items];
  const total = first.total || items.length;
  let page = 2;
  while (items.length < total && page < 40) {
    const next = await fetchPage(page);
    if (!next.items.length) break;
    items.push(...next.items);
    page += 1;
  }
  return { items, total: Math.max(total, items.length) };
};

/** 歌手歌曲用的 key 签名：md5(appid + salt + clientver + clienttime) */
const signParamsKey = (data: number | string): string =>
  md5(`${KG_APPID}${ANDROID_SIGN_SALT}${KG_CLIENTVER}${data}`);

/** 酷狗封面模板换尺寸并升级 https */
const kgCover = (raw: unknown, size = 300): string | undefined =>
  typeof raw === "string" && raw
    ? raw.replace(/^http:\/\//, "https://").replace(/\{size\}/g, String(size))
    : undefined;

/**
 * 酷狗专辑详情与完整曲目
 * @param id - 专辑 id
 * @returns 详情
 */
const kgAlbum = async (id: string): Promise<any> => {
  const detail = await kgGateway(
    "/kmr/v2/albums",
    { dfid: "-", mid: KG_MID, uuid: "-", appid: KG_APPID, clientver: KG_CLIENTVER },
    {
      method: "POST",
      router: "openapi.kugou.com",
      data: {
        data: [{ album_id: id }],
        fields: "album_id,album_name,publish_date,sizable_cover,intro,authors,author_name",
      },
    },
  );
  const info = detail?.data?.[0];
  const songs = await fetchAllPages(async (page) => {
    const res = await kgGateway(
      "/v1/album_audio/lite",
      { dfid: "-", mid: KG_MID, uuid: "-", appid: KG_APPID, clientver: KG_CLIENTVER },
      {
        method: "POST",
        router: "openapi.kugou.com",
        data: { album_id: id, is_buy: "", page, pagesize: 50 },
      },
    );
    const list = Array.isArray(res?.data) ? res.data : (res?.data?.songs ?? []);
    return { items: list, total: res?.total ?? res?.data?.total ?? list.length };
  }, 50);
  console.warn("[kg] album detail n=" + songs.items.length);
  return {
    code: 200,
    id,
    name: info?.album_name ?? "",
    cover: kgCover(info?.sizable_cover),
    coverOriginal: kgCover(info?.sizable_cover, 480),
    artist: info?.author_name ?? "",
    description: info?.intro ?? "",
    publishTime: info?.publish_date ?? "",
    total: songs.items.length,
    songs: songs.items.map((s: any) => {
      const base = s.base ?? {};
      const audio = s.audio_info ?? {};
      const alb = s.album_info ?? {};
      const hash = audio.hash_128 ?? audio.hash ?? "";
      return {
        id: String(base.audio_id ?? hash ?? ""),
        name: base.audio_name ?? "",
        artist: base.author_name ?? "",
        artists: (s.authors ?? []).map((a: any) => ({ name: a.author_name })),
        album: alb.album_name ?? info?.album_name ?? "",
        cover: kgCover(alb.cover ?? info?.sizable_cover),
        // 酷狗这几个 duration 字段本身就是毫秒，不能再乘 1000
        duration: Number(audio.duration ?? audio.duration_128 ?? 0),
        hash: String(hash).toLowerCase(),
        albumAudioId: base.album_audio_id,
        albumId: base.album_id,
      };
    }),
  };
};

/**
 * 酷狗歌手详情
 * @param id - 歌手 id
 * @returns 详情
 */
const kgArtist = async (id: string): Promise<any> => {
  const clienttime = Math.floor(Date.now() / 1000);
  const detail = await kgGateway(
    "/kmr/v3/author",
    { dfid: "-", mid: KG_MID, uuid: "-", appid: KG_APPID, clientver: KG_CLIENTVER },
    { method: "POST", router: "openapi.kugou.com", data: { author_id: id } },
  );
  const songs = await fetchAllPages(async (page) => {
    const res = await kgGateway(
      "/kmr/v1/audio_group/author",
      { dfid: "-", mid: KG_MID, uuid: "-", appid: KG_APPID, clientver: KG_CLIENTVER },
      {
        method: "POST",
        base: "https://openapi.kugou.com",
        router: "openapi.kugou.com",
        data: {
          author_id: id,
          pagesize: 300,
          page,
          sort: 1,
          area_code: "all",
          clienttime,
          key: signParamsKey(clienttime),
        },
      },
    );
    const list = Array.isArray(res?.data) ? res.data : [];
    return { items: list, total: res?.total ?? list.length };
  }, 300);
  console.warn("[kg] artist detail songs=" + songs.items.length);
  return {
    code: 200,
    artist: {
      id,
      name: detail?.data?.author_name ?? "",
      cover: kgCover(detail?.data?.sizable_avatar),
      intro: detail?.data?.intro ?? "",
      songCount: songs.items.length,
      albumCount: detail?.data?.album_count ?? 0,
      fansCount: detail?.data?.fans_num ?? 0,
    },
    songs: songs.items.map((s: any) => ({
      id: String(s.hash ?? s.audio_id ?? "").toLowerCase(),
      name: s.song_name ?? s.audio_name ?? "",
      artist: s.author_name ?? "",
      artists: [{ name: s.author_name ?? "" }],
      album: s.album_name ?? "",
      // 歌手歌曲自身常无封面，兜底用专辑封面或歌手头像
      cover: kgCover(
        s.album_info?.cover ?? s.trans_param?.union_cover ?? detail?.data?.sizable_avatar,
      ),
      duration: Number(s.timelen ?? s.duration ?? 0),
      hash: String(s.hash ?? "").toLowerCase(),
    })),
  };
};

/**
 * 酷狗歌单详情（兼容旧数字 id）
 * @param id - 歌单 id
 * @returns 详情
 */
const kgPlaylist = async (id: string): Promise<any> => {
  let globalId = id;
  if (/^\d+$/.test(id)) {
    const legacy = await httpJson<any>({
      url: `${CDN_HOST}/api/v3/special/info?specialid=${id}&format=json`,
      headers: { Referer: "https://www.kugou.com/" },
    });
    globalId = legacy?.data?.global_specialid ?? id;
  }
  let info: any = null;
  const songs = await fetchAllPages(async (page) => {
    const res = await kgGateway("/pubsongs/v2/get_other_list_file_nofilt", {
      global_collection_id: globalId,
      begin_idx: (page - 1) * 300,
      pagesize: 300,
      area_code: 1,
      plat: 1,
      type: 1,
      mode: 1,
      personal_switch: 1,
    }, { router: "" });
    if (page === 1) info = res?.data?.list_info ?? null;
    const list = res?.data?.songs ?? [];
    return { items: list, total: res?.data?.count ?? list.length };
  }, 300);
  console.warn("[kg] playlist detail n=" + songs.items.length);
  return {
    code: 200,
    id: globalId,
    name: info?.name ?? "",
    creator: info?.list_create_username ?? "",
    description: info?.intro ?? "",
    cover: kgCover(info?.pic),
    coverOriginal: kgCover(info?.pic, 480),
    total: songs.items.length,
    songs: songs.items.map((s: any) => {
      // name 形如「歌手 - 歌名」，按桌面的做法拆开
      const raw = String(s.name ?? s.songname ?? s.filename ?? "");
      const sep = raw.indexOf(" - ");
      const artist = sep > 0 ? raw.slice(0, sep) : "";
      const title = sep > 0 ? raw.slice(sep + 3) : raw;
      const alb = s.albuminfo ?? {};
      return {
        id: String(s.hash ?? "").toLowerCase(),
        name: title,
        artist,
        artists: artist ? [{ name: artist }] : [],
        album: alb.name ?? alb.album_name ?? "",
        // 歌单歌曲的 albuminfo 常无图，兜底用歌单封面
        cover: kgCover(alb.cover ?? alb.img ?? info?.pic),
        duration: Number(s.timelen ?? s.timelength ?? 0),
        hash: String(s.hash ?? "").toLowerCase(),
      };
    }),
  };
};

/** 平台响应 */
export interface KgResponse {
  ok: boolean;
  status?: number;
  body?: unknown;
  error?: string;
}

/**
 * 调用酷狗接口
 * @param name - 接口名
 * @param params - 业务参数
 * @returns 统一响应
 */
export const kugouCall = async (
  name: string,
  params: Record<string, unknown> = {},
): Promise<KgResponse> => {
  try {
    if (name === "login_qr_key") return { ok: true, status: 200, body: await loginQrKey() };
    if (name === "login_qr_check") {
      return { ok: true, status: 200, body: await loginQrCheck(String(params.key ?? "")) };
    }
    // 详情：三类都走签名网关
    if (name === "album" || name === "album_detail") {
      const id = String(params.id ?? params.album_id ?? params.albumid ?? "");
      if (!id) return { ok: false, error: "id required" };
      return (await kgAlbum(id)) as unknown as KgResponse;
    }
    if (name === "artist" || name === "artist_detail") {
      const id = String(params.id ?? params.author_id ?? params.artist_id ?? params.authorid ?? "");
      if (!id) return { ok: false, error: "id required" };
      return (await kgArtist(id)) as unknown as KgResponse;
    }
    if (name === "playlist" || name === "song_list" || name === "special") {
      const id = String(
        params.id ?? params.specialid ?? params.special_id ?? params.global_collection_id ?? "",
      );
      if (!id) return { ok: false, error: "id required" };
      return (await kgPlaylist(id)) as unknown as KgResponse;
    }
    if (name === "search" || name === "search_song") {
      // 前端 res.data 直接取用，这里必须是 { songs, total } 本身
      // search 已经返回业务结构，这里原样交给 dispatch 的信封兜底
      const result = await search(params);
      console.warn("[kg] return keys=" + Object.keys(result).join(","));
      return result as unknown as KgResponse;
    }
    if (name === "lyric") {
      const body = await fetchKugouLyric({
        name: String(params.name ?? params.keyword ?? ""),
        hash: String(params.hash ?? ""),
        durationMs: Number(params.durationMs ?? 0),
      });
      return { ok: true, status: 200, body };
    }
    if (name === "user_detail" || name === "login_status") {
      const cookies = getCookies("kugou");
      if (!cookies.token) return { ok: true, status: 200, body: { code: 200, loggedIn: false } };
      return {
        ok: true,
        status: 200,
        body: { code: 200, loggedIn: true, profile: { userId: cookies.userid, nickname: "" } },
      };
    }
    return { ok: false, error: `酷狗接口未移植: ${name}` };
  } catch (error) {
    return { ok: false, error: error instanceof Error ? error.message : String(error) };
  }
};

/**
 * 清空酷狗登录态
 */
export const clearKugouSession = (): void => {
  setCookies("kugou", {});
};

/**
 * 手动写入酷狗 Cookie
 * @param cookie - Cookie 字符串
 */
export const setKugouCookie = (cookie: string): void => {
  const current = getCookies("kugou");
  for (const part of cookie.split(";")) {
    const eq = part.indexOf("=");
    if (eq <= 0) continue;
    current[part.slice(0, eq).trim()] = part.slice(eq + 1).trim();
  }
  setCookies("kugou", current);
};
