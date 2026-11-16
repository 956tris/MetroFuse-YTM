/* MetroFuse contributors, GPL-3.0. See SOURCE for provenance and limits. */
import {videoId,search,page,home,homeContinuation,radio,library,accountName} from './modules/catalog.js';
import {jsonValue,guestMusicContext,selectAudio,signaturePlan} from './modules/media.js';
import {loginRequest,loginUi,validateCookie,authorization} from './modules/account.js';

let host, bootstrapPromise, playerScriptPromise, playerScriptUrl;
let cookie = null;
const players = new Map();
// Guest player profile from InnerTubeX v0.11.7, without account credentials.
const GUEST_PLAYER = {clientName:'ANDROID_VR',clientVersion:'1.65.10',clientId:'28',osName:'Android',osVersion:'12L',
  deviceMake:'Oculus',deviceModel:'Quest 3',androidSdkVersion:'32',
  userAgent:'com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip'};

async function bootstrap() {
  if (!bootstrapPromise) bootstrapPromise = (async () => {
    const response = await host.http({url:'https://music.youtube.com/sw.js_data',maxBytes:2097152,timeoutMs:10000});
    if (response.status !== 200) throw Error('YouTube Music bootstrap HTTP '+response.status);
    const context = guestMusicContext(response.body);
    if (!context?.client?.clientName || !context.client.clientVersion) throw Error('BOOTSTRAP_UNAVAILABLE');
    return {context};
  })().catch(error => { bootstrapPromise = null; throw error; });
  return bootstrapPromise;
}

async function api(method, fields, profile = null, sessionCookie = cookie, locale = null) {
  const config = await bootstrap(), context = JSON.parse(JSON.stringify(config.context));
  if (profile) {
    const {clientId,...client} = profile;
    context.client = {...context.client,...client};
  }
  const language = locale?.language ?? await host.settings.get('contentLanguage'), country = locale?.country ?? await host.settings.get('contentCountry');
  if (language) context.client.hl = language;
  if (country) context.client.gl = country;
  const response = await host.http({url:'https://music.youtube.com/youtubei/v1/'+method+'?prettyPrint=false',
    method:'POST',headers:{'Content-Type':'application/json','X-Goog-Api-Format-Version':'1','X-YouTube-Client-Name':profile?.clientId ?? '67',
      'X-YouTube-Client-Version':context.client.clientVersion,Origin:'https://music.youtube.com','X-Origin':'https://music.youtube.com',Referer:'https://music.youtube.com/',
      ...(profile?.userAgent && {'User-Agent':profile.userAgent}),
      ...(sessionCookie && !profile && {Cookie:sessionCookie,Authorization:authorization(sessionCookie),'X-Goog-AuthUser':'0'})},
    body:JSON.stringify({context,...fields}),maxBytes:2097152,timeoutMs:10000});
  if (response.status !== 200) throw Error('YouTube Music '+method+' HTTP '+response.status);
  return JSON.parse(response.body);
}

async function player(id) {
  const existing = players.get(id);
  if (existing && existing.expires > Date.now()) return existing.value;
  const fields = {videoId:id,contentCheckOk:true,racyCheckOk:true};
  let value = await api('player',fields);
  if (value.playabilityStatus?.status !== 'OK' || !value.streamingData?.adaptiveFormats?.some(format => /^audio\//.test(format.mimeType ?? ''))) {
    const guest = await api('player',fields,GUEST_PLAYER);
    value = guest;
  }
  if (value.videoDetails?.videoId && value.videoDetails.videoId !== id) throw Error('PLAYER_ID_MISMATCH');
  players.set(id,{value,expires:Date.now()+30000});
  while (players.size > 40) players.delete(players.keys().next().value);
  return value;
}

async function decipherPlan(value) {
  let path = value.assets?.js;
  if (!path && videoId({canonicalId:value.videoDetails?.videoId})) {
    const response = await host.http({url:'https://www.youtube-nocookie.com/embed/'+value.videoDetails.videoId,maxBytes:2097152,timeoutMs:10000});
    if (response.status === 200) path = jsonValue(response.body,'jsUrl');
  }
  if (typeof path !== 'string') return null;
  const url = path.startsWith('/') ? 'https://www.youtube.com'+path : path;
  if (!/^https:\/\/(?:music\.|www\.)?youtube\.com\/s\/player\//.test(url)) return null;
  if (!playerScriptPromise || playerScriptUrl !== url) {
    playerScriptUrl = url;
    playerScriptPromise = (async () => {
      const response = await host.http({url,maxBytes:2097152,timeoutMs:10000});
      return response.status === 200 ? signaturePlan(response.body) : null;
    })().catch(() => { playerScriptPromise = null; playerScriptUrl = null; return null; });
  }
  return playerScriptPromise;
}

async function browse(id, type) {
  if (typeof id !== 'string' || !/^[A-Za-z0-9_-]{1,240}$/.test(id)) return null;
  const browseId = type === 'playlist' && !id.startsWith('VL') ? 'VL'+id : id;
  return page(await api('browse',{browseId}),id,type);
}

const LIBRARY_CATEGORIES=['FEmusic_liked_videos','FEmusic_liked_albums','FEmusic_library_corpus_artists','FEmusic_liked_playlists'];
async function accountLibrary(request) {
  const session=cookie;
  if (!session) return {items:[],complete:true};
  const categories=request.likedSongsOnly ? ['VLLM'] : LIBRARY_CATEGORIES;
  const prefix=request.likedSongsOnly ? 'ytm-likes:' : 'ytm-library:';
  let cursor={section:0};
  if (request.continuation != null) {
    if (typeof request.continuation!=='string' || !request.continuation.startsWith(prefix) || request.continuation.length>16384) throw Error('INVALID_CONTINUATION');
    cursor=JSON.parse(request.continuation.slice(prefix.length));
    if (!Number.isInteger(cursor.section) || cursor.section<0 || cursor.section>=categories.length ||
      (cursor.token!=null && (typeof cursor.token!=='string' || !cursor.token.trim()))) throw Error('INVALID_CONTINUATION');
  }
  let result;
  try {
    result=library(await api('browse',cursor.token ? {continuation:cursor.token} : {browseId:categories[cursor.section]},null,session,request));
  } catch (error) {
    if (cookie!==session) throw Error('ACCOUNT_CHANGED');
    result={items:[],complete:false};
  }
  if (cookie!==session) throw Error('ACCOUNT_CHANGED');
  const next=result.continuation ? {section:cursor.section,token:result.continuation}
    : cursor.section+1<categories.length ? {section:cursor.section+1} : null;
  const items=request.likedSongsOnly ? result.items.map(item => item.track
    ? {...item,track:{...item.track,extras:{...item.track.extras,likeStatus:'LIKE'}}} : item) : result.items;
  return {...result,items,continuation:next ? prefix+JSON.stringify(next) : null};
}

globalThis.plugin = {
  async init(context) {
    host = context;
    host.login({
      request: async () => loginRequest,
      ui: async () => loginUi,
      login: async credentials => {
        const candidate = validateCookie(credentials?.values?.cookie);
        const name = accountName(await api('account/account_menu',{},null,candidate));
        if (!name) throw Error('ACCOUNT_VERIFICATION_FAILED');
        cookie = candidate;
        players.clear();
        const state = {loggedIn:true,accountName:name};
        host.loginState(state);
        return state;
      },
      logout: async () => { cookie=null; players.clear(); host.loginState({loggedIn:false}); }
    });
    host.home(async request => {
      const session = cookie;
      const response = await api('browse',request.continuation ? {continuation:request.continuation} : {browseId:'FEmusic_home'},null,session,request);
      if (cookie !== session) throw Error('ACCOUNT_CHANGED');
      return {shelves:home(response),continuation:homeContinuation(response)};
    });
    host.search('youtube-music.search',async request => String(request.query ?? '').trim()
      ? search(await api('search',{query:request.query}),request.limit) : {tracks:[]});
    host.catalog('youtube-music.catalog',{
      album:id => browse(id,'album'),artist:id => browse(id,'artist'),playlist:id => browse(id,'playlist'),
      library:accountLibrary,
      supportsLikedSongs:true,
      setSongVote:async request => {
        const id=videoId({canonicalId:request.trackId}), session=cookie;
        if (!id || !session) return false;
        if (!['LIKE','DISLIKE','INDIFFERENT'].includes(request.vote)) throw Error('INVALID_VOTE');
        const method={LIKE:'like/like',DISLIKE:'like/dislike',INDIFFERENT:'like/removelike'}[request.vote];
        const response=await api(method,{target:{videoId:id}},null,session);
        if (cookie!==session) throw Error('ACCOUNT_CHANGED');
        return !response.error && response.responseContext?.mainAppWebResponseContext?.loggedOut!==true;
      },
      recommendations:async request => {
        const id = videoId(request.identity);
        if (!id) return {tracks:[]};
        if (request.continuation != null && (typeof request.continuation !== 'string' || request.continuation.length > 16384)) throw Error('INVALID_CONTINUATION');
        const session = cookie;
        const response = radio(await api('next',request.continuation ? {continuation:request.continuation} : {videoId:id,playlistId:'RDAMVM'+id}));
        if (cookie !== session) throw Error('ACCOUNT_CHANGED');
        return {...response,tracks:response.tracks.slice(0,Math.max(1,Math.min(Number(request.limit) || 100,100)))};
      }
    });
    host.metadataSource('youtube-music.metadata',async request => {
      const id = videoId(request.identity);
      if (!id) return null;
      const details = (await player(id)).videoDetails;
      if (!details || details.videoId !== id) return null;
      const fields = {};
      if (typeof details.title === 'string' && details.title.trim()) fields.title = details.title;
      if (typeof details.author === 'string' && details.author.trim()) fields.artist = details.author;
      const duration = Number(details.lengthSeconds)*1000;
      if (Number.isSafeInteger(duration) && duration > 0) fields.duration = duration;
      if (!Object.keys(fields).length) return null;
      return {identity:{...request.identity,identifiers:{...request.identity.identifiers,youtube:id}},
        fields,confidence:1};
    });
    host.audioSource('youtube-music.audio',{transports:['RANGE_HTTP']},async request => {
      const id = videoId(request.identity);
      if (!id) return {error:{code:'IDENTITY_REQUIRED'}};
      if (!await host.settings.get('audioEnabled')) return {error:{code:'AUDIO_DISABLED'}};
      if (request.quality?.lossless) return {error:{code:'LOSSLESS_UNAVAILABLE'}};
      const value = await player(id), result = selectAudio(value,request.quality);
      return result.error?.code === 'SIGNATURE_TRANSFORM_UNAVAILABLE'
        ? selectAudio(value,request.quality,await decipherPlan(value)) : result;
    });
  },
  dispose() { cookie = null; players.clear(); bootstrapPromise = null; playerScriptPromise = null; playerScriptUrl = null; host = null; }
};
