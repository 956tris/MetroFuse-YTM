export const loginRequest = {
  fields: [{id: 'cookie', label: 'YouTube Music session', secret: true}],
  instructions: "Sign in to YouTube Music in the browser window. MetroFuse will finish automatically and store this provider's session encrypted on this device."
};

export const webLoginRequest = {
  url: 'https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com',
  cookieUrl: 'https://music.youtube.com', cookieField: 'cookie',
  requiredCookies: ['SAPISID','__Secure-3PAPISID','__Secure-1PAPISID'],
  allowedHosts: ['music.youtube.com','www.youtube.com','accounts.google.com','consent.google.com','myaccount.google.com','www.google.com']
};

export const loginUi = {
  request: webLoginRequest,
  labels: {
    title: 'YouTube Music', hint: loginRequest.instructions,
    error: 'Could not sign in. Please try again.', retry: 'Try again',
    cancel: 'Cancel', login: 'Sign in', logout: 'Sign out'
  }
};

export function validateCookie(value) {
  if (typeof value !== 'string' || !value.trim() || value.length > 16384 || /[\r\n]/.test(value)) throw Error('INVALID_COOKIE');
  const cookie = value.trim();
  if (!sapisid(cookie)) throw Error('AUTHENTICATION_COOKIE_MISSING');
  return cookie;
}

function sapisid(cookie) {
  const values = Object.fromEntries(cookie.split(';').map(part => {
    const index = part.indexOf('=');
    return [part.slice(0,index).trim(),part.slice(index+1).trim()];
  }).filter(([name,value]) => name && value));
  return values.SAPISID || values['__Secure-3PAPISID'] || values['__Secure-1PAPISID'];
}

export function authorization(cookie, now = Date.now()) {
  const secret = sapisid(cookie);
  if (!secret) throw Error('AUTHENTICATION_COOKIE_MISSING');
  const timestamp = Math.floor(now/1000);
  return 'SAPISIDHASH '+timestamp+'_'+sha1(timestamp+' '+secret+' https://music.youtube.com');
}

export function sha1(value) {
  const bytes = Array.from(encodeURIComponent(value).replace(/%([a-f0-9]{2})/gi,(_,hex) => String.fromCharCode(parseInt(hex,16))),c => c.charCodeAt(0));
  const length = bytes.length;
  bytes.push(128);
  while (bytes.length%64 !== 56) bytes.push(0);
  bytes.push(0,0,0,0,(length*8)>>>24,(length*8)>>>16&255,(length*8)>>>8&255,(length*8)&255);
  const state = [0x67452301,0xefcdab89,0x98badcfe,0x10325476,0xc3d2e1f0];
  const rotate = (n,bits) => (n<<bits)|(n>>>(32-bits));
  for (let offset=0; offset<bytes.length; offset+=64) {
    const words = Array(80).fill(0);
    for (let i=0; i<16; i++) words[i]=(bytes[offset+i*4]<<24)|(bytes[offset+i*4+1]<<16)|(bytes[offset+i*4+2]<<8)|bytes[offset+i*4+3];
    for (let i=16; i<80; i++) words[i]=rotate(words[i-3]^words[i-8]^words[i-14]^words[i-16],1);
    let [a,b,c,d,e] = state;
    for (let i=0; i<80; i++) {
      const f=i<20 ? (b&c)|(~b&d) : i<40 ? b^c^d : i<60 ? (b&c)|(b&d)|(c&d) : b^c^d;
      const k=i<20 ? 0x5a827999 : i<40 ? 0x6ed9eba1 : i<60 ? 0x8f1bbcdc : 0xca62c1d6;
      const temp=(rotate(a,5)+f+e+k+words[i])|0;
      e=d; d=c; c=rotate(b,30); b=a; a=temp;
    }
    [a,b,c,d,e].forEach((n,i) => { state[i]=(state[i]+n)|0; });
  }
  return state.map(n => (n>>>0).toString(16).padStart(8,'0')).join('');
}
