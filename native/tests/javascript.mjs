import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import {createHash} from 'node:crypto';
const source = new URL('../../universal/',import.meta.url);
const catalogUrl = 'data:text/javascript;base64,'+Buffer.from(await fs.readFile(new URL('modules/catalog.js',source))).toString('base64');
const mediaUrl = 'data:text/javascript;base64,'+Buffer.from(await fs.readFile(new URL('modules/media.js',source))).toString('base64');
const accountUrl = 'data:text/javascript;base64,'+Buffer.from(await fs.readFile(new URL('modules/account.js',source))).toString('base64');
const catalog = await import(catalogUrl);
const media = await import(mediaUrl);
const account = await import(accountUrl);
assert.equal(account.sha1('abc'),'a9993e364706816aba3e25717850c26c9cd0d89d');
assert.equal(account.sha1(''),createHash('sha1').update('').digest('hex'));
assert.equal(account.sha1('caf\u00e9'),createHash('sha1').update('caf\u00e9').digest('hex'));
const expectedHash=createHash('sha1').update('1700000000 fixture https://music.youtube.com').digest('hex');
assert.equal(account.authorization('SAPISID=fixture',1700000000000),'SAPISIDHASH 1700000000_'+expectedHash);
assert.throws(() => account.validateCookie('SAPISID=fixture\r\nInvalid: secret'));
const fixture = JSON.parse(await fs.readFile(new URL('../src/desktopTest/resources/youtube-search.json',import.meta.url)));
const radioResponse = JSON.parse(await fs.readFile(new URL('../src/desktopTest/resources/youtube-radio.json',import.meta.url)));
const homeResponse = JSON.parse(await fs.readFile(new URL('../src/desktopTest/resources/youtube-home.json',import.meta.url)));
const homeContinuationResponse = JSON.parse(await fs.readFile(new URL('../src/desktopTest/resources/youtube-home-continuation.json',import.meta.url)));
const libraryResponse = JSON.parse(await fs.readFile(new URL('../src/desktopTest/resources/youtube-library.json',import.meta.url)));
const libraryContinuationResponse = JSON.parse(await fs.readFile(new URL('../src/desktopTest/resources/youtube-library-continuation.json',import.meta.url)));
assert.equal(catalog.library(libraryResponse).continuation,'library-next');
assert.deepEqual(catalog.library(libraryResponse).items.map(item=>Object.keys(item)[0]),['track','album','artist','playlist']);
assert.equal(catalog.library(libraryContinuationResponse).items[0].track.id,'lmnopqrstuv');
assert.equal(catalog.library(libraryContinuationResponse).continuation,null);
assert.throws(()=>catalog.library({}),/LIBRARY_CONTENTS_MISSING/);
const radioPanel = radioResponse.contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer.watchNextTabbedResultsRenderer.tabs[0].tabRenderer.content.musicQueueRenderer.content.playlistPanelRenderer;
const continuedRadio = {continuationContents:{playlistPanelContinuation:radioPanel}};
for (const root of [radioResponse,continuedRadio]) {
  const result = catalog.radio(root);
  assert.deepEqual(result.tracks.map(value => value.id),['abcdefghijk','lmnopqrstuv']);
  assert.equal(result.continuation,'radio-next');
  assert.equal(result.tracks[1].duration,241000);
  assert.equal(result.tracks[1].artwork,'https://example.org/related.jpg');
  assert.equal(result.tracks[1].extras.musicVideoType,'MUSIC_VIDEO_TYPE_ATV');
  assert.equal(result.tracks[1].extras.explicit,'true');
}
assert.deepEqual(catalog.home(homeResponse).map(value => value.title),['Listen again','Quick picks']);
assert.equal(catalog.homeContinuation(homeResponse),'home-next');
const moreHome = catalog.home(homeContinuationResponse);
assert.deepEqual(moreHome.map(value => value.title),['New releases','Mixes for you','Artists for you','Featured recommendation']);
assert.equal(moreHome[0].items[0].album.id,'MPREfixture');
assert.equal(moreHome[1].items[0].playlist.id,'RDCLAKfixture');
assert.equal(moreHome[1].items[0].playlist.artwork,'https://example.org/mix.jpg');
assert.equal(moreHome[2].items[0].artist.id,'UCfixture');
assert.equal(moreHome[3].items[0].track.id,'wxyz0123456');
assert.equal(catalog.homeContinuation(homeContinuationResponse),null);
const results = catalog.search(fixture,10);
assert.equal(results.tracks.length,1);
assert.equal(results.tracks[0].extras.albumId,'MPREfixture');
assert.equal(results.tracks[0].duration,241000);
assert.equal(results.albums[0].id,'MPREfixture');
assert.equal(results.artists[0].id,'UCfixture');
assert.equal(results.playlists[0].id,'VLPLfixture');
assert.equal(catalog.page(fixture,'PLfixture','playlist').tracks.length,2);
assert.equal(catalog.page(fixture,'MPREfixture','album').tracks.length,1);
assert.equal(catalog.videoId({canonicalId:'abcdefghijk',identifiers:{pluginOwner:'metrofuse.jiosaavn'}}),null);
assert.equal(media.jsonValue('ytcfg.set({"INNERTUBE_CONTEXT":{"client":{"clientName":"WEB_REMIX","clientVersion":"fixture"}}});','INNERTUBE_CONTEXT').client.clientName,'WEB_REMIX');
const tuple = Array(17).fill(null);
tuple[0] = 'pt-BR'; tuple[1] = 'BR'; tuple[15] = 67; tuple[16] = '1.20261114.00.00';
assert.deepEqual(media.guestMusicContext(")]}'\n"+JSON.stringify([['yt.sw.adr',null,[[tuple]]]])),{client:{clientName:'WEB_REMIX',clientVersion:'1.20261114.00.00',hl:'pt-BR',gl:'BR'},user:{}});
assert.equal(media.guestMusicContext('invalid bootstrap'),null);
tuple[15] = 28;
assert.equal(media.guestMusicContext(JSON.stringify(tuple)),null);
const player = {playabilityStatus:{status:'OK'},videoDetails:{videoId:'abcdefghijk',lengthSeconds:'241'},streamingData:{adaptiveFormats:[
  {itag:140,mimeType:'audio/mp4; codecs="mp4a.40.2"',bitrate:128000,audioSampleRate:'44100',url:'https://fixture.googlevideo.com/videoplayback?expire=4102444800'},
  {itag:251,mimeType:'audio/webm; codecs="opus"',bitrate:160000,audioSampleRate:'48000',url:'https://fixture.googlevideo.com/videoplayback?expire=4102444800'}
]}};
assert.equal(media.selectAudio(player,{preset:'HIGH'}).stream.codec,'opus');
assert.equal(media.selectAudio(player,{preset:'LOW'}).stream.codec,'mp4a.40.2');
assert.equal(media.selectAudio(player,{preset:'MP4'}).stream.codec,'mp4a.40.2');
assert.equal(media.selectAudio(player,{maxBitrate:130000}).stream.bitrate,128000);
assert.equal(media.selectAudio(player,{maxBitrate:100000}).error.code,'FORMAT_UNAVAILABLE');
assert.equal(media.selectAudio(player,{lossless:true}).error.code,'LOSSLESS_UNAVAILABLE');
const ciphered = structuredClone(player);
ciphered.streamingData.adaptiveFormats = [{itag:140,mimeType:'audio/mp4',signatureCipher:'url='+encodeURIComponent('https://fixture.googlevideo.com/videoplayback?expire=4102444800')+'&sp=sig&s=abcdef'}];
assert.equal(media.selectAudio(ciphered).error.code,'SIGNATURE_TRANSFORM_UNAVAILABLE');
const script = 'var XY={rv:function(a){a.reverse()},sp:function(a,b){a.splice(0,b)},sw:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c}};function(a){a=a.split("");XY.sw(a,2);XY.rv(a);XY.sp(a,1);return a.join("")}';
const plan = media.signaturePlan(script);
assert.deepEqual(plan,[['swap',2],['reverse',0],['splice',1]]);
assert.equal(media.decipherSignature('abcdef',plan),'edabc');
assert.match(media.selectAudio(ciphered,{},plan).stream.locator,/sig=edabc$/);
const blocked = structuredClone(player);
blocked.streamingData.adaptiveFormats.forEach(format => format.url += '&n=throttled');
assert.equal(media.selectAudio(blocked).error.code,'N_TRANSFORM_UNAVAILABLE');
assert.equal(media.signaturePlan('unsupported-player-code'),null);
const entry = (await fs.readFile(new URL('index.js',source),'utf8')).replace("'./modules/catalog.js'",JSON.stringify(catalogUrl)).replace("'./modules/media.js'",JSON.stringify(mediaUrl)).replace("'./modules/account.js'",JSON.stringify(accountUrl));
await import('data:text/javascript;base64,'+Buffer.from(entry).toString('base64'));
tuple[15] = 67;
const settings = {audioEnabled:true,contentLanguage:'es-ES',contentCountry:'ES'};
const providers = {}, requests = [];
let authenticated = false;
let validAccount = true;
let failLibraryCategory = false;
const host = {
  login(callbacks) { providers.login=callbacks; },
  loginState(state) { providers.account=state; },
  home(callback) { providers.home=callback; },
  search(id,callback) { providers.search = callback; },
  catalog(id,callback) { providers.catalog = callback; },
  audioSource(id,capabilities,callback) { providers.audio = callback; },
  metadataSource(id,callback) { providers.metadata = callback; },
  settings:{async get(key) { return settings[key]; }},
  async http(request) {
    requests.push(request);
    if (request.url.endsWith('/sw.js_data')) return {status:200,headers:{},body:")]}'\n"+JSON.stringify([['yt.sw.adr',null,[[tuple]]]])};
    const body = JSON.parse(request.body);
    if (request.url.includes('/account/account_menu')) {
      assert.equal(request.headers.Cookie,'SAPISID=fixture');
      assert.match(request.headers.Authorization,/^SAPISIDHASH [0-9]+_[a-f0-9]{40}$/);
      return {status:200,headers:{},body:JSON.stringify(validAccount ? {activeAccountHeaderRenderer:{accountName:{runs:[{text:'Fixture account'}]}}} : {})};
    }
    const isHome = body.browseId === 'FEmusic_home' || body.continuation === 'home-next';
    assert.equal(body.context.client.hl,isHome ? 'fr' : 'es-ES');
    assert.equal(body.context.client.gl,isHome ? 'FR' : 'ES');
    assert.equal(request.headers.Cookie,authenticated ? 'SAPISID=fixture' : undefined);
    if (body.browseId==='FEmusic_liked_videos') return {status:200,headers:{},body:JSON.stringify(libraryResponse)};
    if (body.continuation==='library-next') {
      assert.equal(body.browseId,undefined);
      return {status:200,headers:{},body:JSON.stringify(libraryContinuationResponse)};
    }
    if (['FEmusic_liked_albums','FEmusic_library_corpus_artists','FEmusic_liked_playlists'].includes(body.browseId)) {
      return failLibraryCategory && body.browseId==='FEmusic_liked_albums' ? {status:503,body:'{}'}
        : {status:200,headers:{},body:'{"contents":{"sectionListRenderer":{"contents":[]}}}'};
    }
    if (body.browseId === 'FEmusic_home') return {status:200,headers:{},body:JSON.stringify(homeResponse)};
    if (body.continuation === 'home-next') {
      assert.equal(body.browseId,undefined);
      return {status:200,headers:{},body:JSON.stringify(homeContinuationResponse)};
    }
    if (request.url.includes('/next')) {
      if (body.continuation) {
        assert.equal(body.continuation,'radio-next');
        assert.equal(body.videoId,undefined);
        return {status:200,headers:{},body:JSON.stringify(continuedRadio)};
      }
      assert.equal(body.videoId,'abcdefghijk');
      assert.equal(body.playlistId,'RDAMVMabcdefghijk');
      return {status:200,headers:{},body:JSON.stringify(radioResponse)};
    }
    return {status:200,headers:{},body:JSON.stringify(request.url.includes('/player') ? player : fixture)};
  }
};
await globalThis.plugin.init(host);
assert.equal((await providers.search({query:'Fixture',limit:10})).tracks[0].title,'Fixture track');
assert.equal((await providers.catalog.playlist('PLfixture')).tracks.length,2);
assert.equal((await providers.audio({identity:{canonicalId:'abcdefghijk'},quality:{preset:'HIGH'}})).stream.codec,'opus');
assert.equal(requests.filter(request => request.url.endsWith('/sw.js_data')).length,1);
const homePage = await providers.home({language:'fr',country:'FR'});
assert.equal(homePage.shelves[0].items[0].track.id,'abcdefghijk');
assert.equal(homePage.continuation,'home-next');
assert.equal((await providers.home({language:'fr',country:'FR',continuation:homePage.continuation})).shelves.length,4);
const recommendations = await providers.catalog.recommendations({identity:{canonicalId:'abcdefghijk'}});
assert.deepEqual(recommendations.tracks.map(value => value.id),['abcdefghijk','lmnopqrstuv']);
assert.equal((await providers.catalog.recommendations({identity:{canonicalId:'abcdefghijk'},continuation:recommendations.continuation})).tracks.length,2);
const beforeForeign = requests.length;
assert.deepEqual(await providers.catalog.recommendations({identity:{canonicalId:'abcdefghijk',identifiers:{pluginOwner:'foreign'}}}),{tracks:[]});
assert.equal(requests.length,beforeForeign);
assert.equal((await providers.login.request()).fields[0].id,'cookie');
assert.equal((await providers.login.ui()).request.cookieUrl,'https://music.youtube.com');
assert.match((await providers.login.ui()).request.url,/^https:\/\/accounts\.google\.com\//);
const {request: signIn} = await providers.login.ui();
for (const url of [
  'https://accounts.google.co.uk/accounts/SetSID',
  'https://accounts.youtube.com/accounts/SetSID',
  'https://youtube.com/signin',
  'https://consent.youtube.com/',
  'https://music.youtube.com/'
]) assert.ok(signIn.allowedHosts.includes(new URL(url).hostname), url);
assert.equal((await providers.login.ui()).labels.title,'YouTube Music');
assert.equal(providers.login.web, undefined);
await providers.login.login({values:{cookie:'SAPISID=fixture'}});
authenticated = true;
assert.equal(providers.account.accountName,'Fixture account');
await providers.search({query:'Fixture',limit:10});
await providers.catalog.recommendations({identity:{canonicalId:'abcdefghijk'}});
const libraryStart=requests.length;
let libraryPage=await providers.catalog.library({language:'es-ES',country:'ES'});
const libraryItems=[...libraryPage.items];
while (libraryPage.continuation) {
  libraryPage=await providers.catalog.library({language:'es-ES',country:'ES',continuation:libraryPage.continuation});
  assert.equal(libraryPage.complete,true);
  libraryItems.push(...libraryPage.items);
}
assert.deepEqual(libraryItems.filter(item=>item.track).map(item=>item.track.id),['abcdefghijk','lmnopqrstuv']);
assert.deepEqual(requests.slice(libraryStart).map(request=>JSON.parse(request.body).browseId).filter(Boolean),
  ['FEmusic_liked_videos','FEmusic_liked_albums','FEmusic_library_corpus_artists','FEmusic_liked_playlists']);
failLibraryCategory=true;
const failedLibrary=await providers.catalog.library({language:'es-ES',country:'ES',continuation:'ytm-library:{"section":1}'});
assert.equal(failedLibrary.complete,false);
assert.ok(failedLibrary.continuation);
failLibraryCategory=false;
await assert.rejects(providers.catalog.library({continuation:'invalid'}),/INVALID_CONTINUATION/);
validAccount = false;
await assert.rejects(providers.login.login({values:{cookie:'SAPISID=fixture'}}),/ACCOUNT_VERIFICATION_FAILED/);
assert.equal(providers.account.loggedIn,true);
await providers.login.logout();
authenticated = false;
assert.equal(providers.account.loggedIn,false);
const signedOutLibraryRequests=requests.length;
assert.deepEqual(await providers.catalog.library({language:'es-ES',country:'ES'}),{items:[],complete:true});
assert.equal(requests.length,signedOutLibraryRequests);
await providers.search({query:'Fixture',limit:10});
settings.audioEnabled = false;
const requestCount = requests.length;
assert.equal((await providers.audio({identity:{canonicalId:'abcdefghijk'}})).error.code,'AUDIO_DISABLED');
assert.equal(requests.length,requestCount);
globalThis.plugin.dispose();
console.log('YouTube Music JS production callbacks, paginated Home and library, recommendations, locale, catalog, identity, direct audio, quality and supported/unsupported signature fixtures passed.');
