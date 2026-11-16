/* Adapted from MetroList's YouTubeItemParser and YouTubeSearchOperations, GPL-3.0. */
const ITEMS = new Set(['musicResponsiveListItemRenderer','musicTwoRowItemRenderer','musicMultiRowListItemRenderer','musicCardShelfRenderer']);
const HEADERS = new Set(['musicDetailHeaderRenderer','musicResponsiveHeaderRenderer','musicImmersiveHeaderRenderer','musicVisualHeaderRenderer']);
const SHELVES = new Set(['musicCarouselShelfRenderer','musicShelfRenderer','musicImmersiveCarouselShelfRenderer','gridRenderer','musicGridShelfRenderer','musicCardShelfRenderer']);
const SHELF_HEADERS = new Set(['musicCarouselShelfBasicHeaderRenderer','musicCarouselShelfHeaderRenderer','musicImmersiveCarouselShelfBasicHeaderRenderer','musicImmersiveCarouselShelfHeaderRenderer','gridHeaderRenderer','musicGridShelfBasicHeaderRenderer']);

export function videoId(identity) {
  const identifiers = identity?.identifiers ?? {};
  const owner = identifiers.pluginOwner ?? identifiers.plugin;
  const id = identifiers.youtube ?? (!owner || owner === 'metrofuse.youtube-music' ? identity?.canonicalId : null);
  return typeof id === 'string' && /^[A-Za-z0-9_-]{11}$/.test(id) ? id : null;
}

export function renderers(root, names) {
  const found = [], pending = root == null ? [] : [root];
  while (pending.length) {
    const item = pending.pop();
    if (Array.isArray(item)) pending.push(...item.slice().reverse());
    else if (item && typeof item === 'object') {
      for (const key of Object.keys(item)) if (names.has(key) && item[key] && typeof item[key] === 'object') found.push(item[key]);
      pending.push(...Object.values(item).reverse());
    }
  }
  return found;
}

const runs = value => Array.isArray(value?.runs) ? value.runs : [];
const text = value => typeof value === 'string' ? value.trim() : (value?.simpleText ?? runs(value).map(run => run.text ?? '').join('')).trim();
const endpoint = row => row?.navigationEndpoint ?? row?.onTap ?? row?.playNavigationEndpoint ?? {};
const column = (row, field, index) => row?.[field]?.[index]?.[field === 'flexColumns' ? 'musicResponsiveListItemFlexColumnRenderer' : 'musicResponsiveListItemFixedColumnRenderer']?.text;
const title = row => text(row.title) || text(column(row,'flexColumns',0)) || text(row.secondTitle);
const metadata = row => [row.subtitle,row.secondSubtitle,column(row,'flexColumns',1)].map(runs).find(value => value.length) ?? [];

function kind(browse) {
  const id = browse?.browseId ?? '';
  const page = browse?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType;
  if (page === 'MUSIC_PAGE_TYPE_ALBUM' || id.startsWith('MPRE')) return 'album';
  if (page === 'MUSIC_PAGE_TYPE_ARTIST' || id.startsWith('UC')) return 'artist';
  if (page === 'MUSIC_PAGE_TYPE_PLAYLIST' || /^(VL|PL|OLAK)/.test(id)) return 'playlist';
  return null;
}

function artwork(row) {
  return [row.thumbnail,row.thumbnailRenderer,row.foregroundThumbnail]
    .flatMap(value => [value,...renderers(value,new Set(['musicThumbnailRenderer','croppedSquareThumbnailRenderer','thumbnail']))].filter(Boolean))
    .flatMap(value => value.thumbnails ?? []).map(value => value.url)
    .filter(value => typeof value === 'string' && value.startsWith('https://')).pop() ?? '';
}

function credit(values) {
  return [...new Set(values.filter(value => kind(endpoint(value).browseEndpoint) === 'artist').map(value => text(value.text)).filter(Boolean))].join(', ');
}

function duration(value) {
  if (typeof value !== 'string' || !/^\d{1,3}:\d{1,2}(?::\d{1,2})?$/.test(value.trim())) return null;
  const parts = value.trim().split(':').map(Number);
  if (parts.slice(1).some(part => part >= 60)) return null;
  return parts.reduce((total,part) => total*60+part,0)*1000;
}

function track(row) {
  const watch = endpoint(row).watchEndpoint ?? endpoint(row).watchPlaylistEndpoint;
  const id = row.playlistItemData?.videoId ?? watch?.videoId ?? renderers(row.overlay,new Set(['watchEndpoint']))[0]?.videoId
    ?? runs(column(row,'flexColumns',0)).map(run => endpoint(run).watchEndpoint?.videoId).find(Boolean);
  if (!videoId({canonicalId:id}) || collection(row) || !title(row)) return null;
  const values = metadata(row), album = values.find(value => kind(endpoint(value).browseEndpoint) === 'album');
  return {id,title:title(row),artist:credit(values),album:text(album?.text),
    duration:[text(column(row,'fixedColumns',0)),...values.map(value => value.text)].map(duration).find(value => value != null) ?? 0,
    artwork:artwork(row),extras:trackExtras(id,album,row)};
}

function trackExtras(id,album,row) {
  const type = row.musicVideoType ?? endpoint(row).watchEndpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType;
  const explicit = renderers(row.badges,new Set(['musicInlineBadgeRenderer'])).some(value => value.icon?.iconType === 'MUSIC_EXPLICIT_BADGE');
  const status = renderers(row.menu,new Set(['likeButtonRenderer']))
    .find(value => !value.target?.videoId || value.target.videoId === id)?.likeStatus;
  return {youtube:id,...(album && {albumId:endpoint(album).browseEndpoint.browseId}),...(type && {musicVideoType:type}),
    ...(explicit && {explicit:'true'}),...(['LIKE','DISLIKE','INDIFFERENT'].includes(status) && {likeStatus:status})};
}

const unique = values => [...values.reduce((found,value) => { if (!found.has(value.id)) found.set(value.id,value); return found; },new Map()).values()];

function collection(row) {
  const navigation = endpoint(row), browse = navigation.browseEndpoint;
  const watch = navigation.watchPlaylistEndpoint ?? (!navigation.watchEndpoint?.videoId ? navigation.watchEndpoint : null);
  const id = browse?.browseId ?? watch?.playlistId, type = kind(browse) ?? (watch?.playlistId ? 'playlist' : null);
  if (!id || id.startsWith('FEmusic_library') || !type || !title(row)) return null;
  return {id,title:title(row),type,credit:credit(metadata(row)),artwork:artwork(row)};
}

function catalog(value, type, tracks = [], albums = []) {
  if (type === 'artist') return {id:value.id,name:value.title,artwork:value.artwork,tracks,albums};
  if (type === 'album') return {id:value.id,title:value.title,artist:value.credit,artwork:value.artwork,tracks};
  return {id:value.id,title:value.title,author:value.credit,artwork:value.artwork,tracks};
}

export function search(root, limit) {
  limit = Math.max(1,Math.min(Number(limit) || 30,100));
  const rows = renderers(root.contents,ITEMS), collections = unique(rows.map(collection).filter(Boolean));
  return {tracks:unique(rows.map(track).filter(Boolean)).slice(0,limit),
    albums:collections.filter(value => value.type === 'album').slice(0,limit).map(value => catalog(value,'album')),
    artists:collections.filter(value => value.type === 'artist').slice(0,limit).map(value => catalog(value,'artist')),
    playlists:collections.filter(value => value.type === 'playlist').slice(0,limit).map(value => catalog(value,'playlist'))};
}

export function page(root, id, type) {
  const header = [...renderers(root.header,HEADERS),...renderers(root.contents,HEADERS)].find(value => text(value.title));
  if (!header) return null;
  const rows = renderers(root.contents,ITEMS), tracks = rows.map(track).filter(Boolean);
  const value = {id,title:text(header.title),credit:credit([...runs(header.straplineTextOne),...runs(header.subtitle)]),artwork:artwork(header)};
  const albums = type === 'artist' ? unique(rows.map(collection).filter(value => value?.type === 'album')).map(value => catalog(value,'album')) : [];
  return catalog(value,type,type === 'playlist' ? tracks : unique(tracks),albums);
}

export function home(root) {
  let remaining=1000;
  return homeRoots(root).flatMap(shelves).map(shelf => {
    if (!remaining) return null;
    const header=shelf.header ?? shelf;
    if (renderers(header,new Set(['browseEndpoint'])).some(endpoint => endpoint.browseId?.startsWith('FEmusic_library'))) return null;
    const name=text(shelf.title) || renderers(header,SHELF_HEADERS).map(header => text(header.title)).find(Boolean);
    if (!name) return null;
    const seen = new Set();
    const items=[shelf,...renderers(shelf.contents ?? shelf.items,ITEMS)].map(row => {
      const value=collection(row);
      if (value) return {[value.type]:catalog(value,value.type)};
      const song=track(row);
      return song ? {track:song} : null;
    }).filter(value => {
      if (!value) return false;
      const id = Object.values(value)[0].id;
      if (seen.has(id)) return false;
      seen.add(id);
      return true;
    }).slice(0,Math.min(100,remaining));
    remaining-=items.length;
    return items.length ? {title:name,items,layout:Number(shelf.numItemsPerColumn)>1 ? 'ROWS_GRID' : 'CARDS'} : null;
  }).filter(Boolean).slice(0,50);
}

function responseActions(root) {
  return [root.onResponseReceivedActions,root.onResponseReceivedEndpoints,root.actions]
    .flatMap(value => renderers(value,new Set(['appendContinuationItemsAction','reloadContinuationItemsCommand'])));
}

function homeRoots(root) {
  return [root.contents,root.continuationContents,...responseActions(root).map(value => value.continuationItems)].filter(Boolean);
}

function shelves(root) {
  const found = [], pending = [root];
  while (pending.length) {
    const current = pending.pop();
    if (Array.isArray(current)) pending.push(...current.slice().reverse());
    else if (current && typeof current === 'object') {
      for (const [name,value] of Object.entries(current).reverse()) {
        if (SHELVES.has(name) && value && typeof value === 'object') found.push(value);
        else pending.push(value);
      }
    }
  }
  return found;
}

const validContinuation = value => typeof value === 'string' && value.trim() && value.length <= 16384 ? value : null;
function inlineContinuation(contents) {
  return (Array.isArray(contents) ? contents : []).map(value => validContinuation(value.continuationItemRenderer?.continuationEndpoint?.continuationCommand?.token)).find(Boolean) ?? null;
}

function continuation(container) {
  return (container.continuations ?? []).map(value => validContinuation((value.nextRadioContinuationData ?? value.nextContinuationData)?.continuation)).find(Boolean)
    ?? inlineContinuation(container.contents);
}

export function homeContinuation(root) {
  return homeRoots(root).flatMap(value => renderers(value,new Set(['sectionListRenderer','sectionListContinuation'])))
    .map(continuation).find(Boolean) ?? responseActions(root).map(value => inlineContinuation(value.continuationItems)).find(Boolean) ?? null;
}

export function library(root) {
  if (!root.contents && !root.continuationContents && !responseActions(root).length) throw Error('LIBRARY_CONTENTS_MISSING');
  if (root.responseContext?.mainAppWebResponseContext?.loggedOut === true) throw Error('LIBRARY_SESSION_EXPIRED');
  const rows=homeRoots(root).flatMap(value => renderers(value,ITEMS));
  const seen=new Set(), items=rows.map(row => {
    const value=collection(row);
    if (value) return {[value.type]:catalog(value,value.type)};
    const song=track(row);
    return song ? {track:song} : null;
  }).filter(item => {
    if (!item) return false;
    const [type,value]=Object.entries(item)[0], key=type+':'+value.id;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  if (items.length>1000) throw Error('LIBRARY_PAGE_LIMIT');
  const containers=new Set(['musicShelfRenderer','musicShelfContinuation','musicPlaylistShelfRenderer','musicPlaylistShelfContinuation',
    'gridRenderer','gridContinuation','sectionListRenderer','sectionListContinuation']);
  const tokens=[...new Set([...homeRoots(root).flatMap(value => renderers(value,containers)).map(continuation),
    ...responseActions(root).map(value => inlineContinuation(value.continuationItems))].filter(Boolean))];
  return {items,continuation:tokens.length===1 ? tokens[0] : null,complete:tokens.length<=1};
}

export function radio(root) {
  const panel = root.continuationContents?.playlistPanelContinuation
    ?? renderers(root.contents,new Set(['musicQueueRenderer']))[0]?.content?.playlistPanelRenderer;
  if (!panel) return {tracks:[],continuation:null};
  const tracks = (panel.contents ?? []).map(value => {
    const row = value.playlistPanelVideoRenderer ?? value.playlistPanelVideoWrapperRenderer?.primaryRenderer?.playlistPanelVideoRenderer;
    if (!row || !videoId({canonicalId:row.videoId}) || !text(row.title)) return null;
    const values = runs(row.longBylineText).length ? runs(row.longBylineText) : runs(row.shortBylineText);
    const album = values.find(value => kind(endpoint(value).browseEndpoint) === 'album');
    return {id:row.videoId,title:text(row.title),artist:credit(values) || text(row.shortBylineText),album:text(album?.text),
      duration:duration(text(row.lengthText)) ?? 0,artwork:artwork(row),extras:trackExtras(row.videoId,album,row)};
  }).filter(Boolean);
  return {tracks:unique(tracks).slice(0,100),continuation:continuation(panel)};
}

export function accountName(root) {
  return renderers(root,new Set(['activeAccountHeaderRenderer'])).map(value => text(value.accountName)).find(Boolean) || null;
}
