// Browser regression run for media: photos, animated GIFs and videos in posts, messages, albums, avatars, group
// logos and app logos. See TESTS.md section 7. Fixtures are generated with ffmpeg, so it must be on the PATH.
//
// Usage: NODE_PATH=<dir with playwright> node dev/regression.js
// Environment: BASE_URL (default http://127.0.0.1:4567), LOGIN, PASSWORD (default admin@example.com / password),
//              UPLOAD_PATH (the server's upload.path; when set, stored files are checked on disk)
const {chromium}=require('playwright');
const fs=require('fs');
const os=require('os');
const path=require('path');
const {execFileSync}=require('child_process');

const base=process.env.BASE_URL || 'http://127.0.0.1:4567';
const login=process.env.LOGIN || 'admin@example.com';
const password=process.env.PASSWORD || 'password';
const uploadPath=process.env.UPLOAD_PATH;

let failures=0;
function check(cond, msg){
	console.log((cond ? 'PASS ' : 'FAIL ')+msg);
	if(!cond) failures++;
}

function ffmpeg(args){
	execFileSync('ffmpeg', ['-nostdin', '-y', '-hide_banner', '-loglevel', 'error', ...args], {stdio: 'inherit'});
}

// Generates the test files once, in a temporary directory
function makeFixtures(){
	const dir=fs.mkdtempSync(path.join(os.tmpdir(), 'tusovochka-regression-'));
	const f=name=>path.join(dir, name);
	ffmpeg(['-f', 'lavfi', '-i', 'testsrc=duration=1:size=640x480:rate=1', '-frames:v', '1', f('still.png')]);
	ffmpeg(['-f', 'lavfi', '-i', 'testsrc=duration=1:size=3000x1000:rate=1', '-frames:v', '1', f('big.jpg')]);
	ffmpeg(['-f', 'lavfi', '-i', 'testsrc=duration=1:size=300x300:rate=1', '-frames:v', '1', f('logo.png')]);
	ffmpeg(['-f', 'lavfi', '-i', 'testsrc=duration=1:size=300x200:rate=12', '-frames:v', '12', f('anim.gif')]);
	ffmpeg(['-f', 'lavfi', '-i', 'testsrc=duration=1:size=100x100:rate=1', '-frames:v', '1', f('still.gif')]);
	// 100 frames of 800x800 are 64 megapixels, over the 50 megapixel budget, so this one gets scaled down
	ffmpeg(['-f', 'lavfi', '-i', 'testsrc=duration=5:size=800x800:rate=20', '-frames:v', '100', f('huge.gif')]);
	ffmpeg(['-f', 'lavfi', '-i', 'testsrc=duration=4:size=1280x960:rate=25', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=4', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-shortest', f('clip.mp4')]);
	fs.writeFileSync(f('garbage.mp4'), 'this is not a video');
	return {dir, f};
}

async function upload(page, url, field, file, name, type){
	const bytes=Array.from(fs.readFileSync(file));
	return await page.evaluate(async ({url, field, bytes, name, type})=>{
		const fd=new FormData();
		fd.append(field, new File([new Uint8Array(bytes)], name, {type}));
		const r=await fetch(url+(url.includes('?') ? '&' : '?')+'_ajax=1&csrf='+userConfig.csrf, {method: 'POST', body: fd});
		return {status: r.status, body: await r.text()};
	}, {url, field, bytes, name, type});
}

async function postWithAttachment(page, wallPath, text, attachmentID){
	await page.goto(base+wallPath);
	await Promise.all([page.waitForNavigation(), page.evaluate(({text, id})=>{
		const form=document.querySelector('form[action*="createWallPost"]');
		form.querySelector('textarea[name=text]').value=text;
		form.querySelector('input[name=attachments]').value=id;
		form.submit();
	}, {text, id: attachmentID})]);
	await page.goto(base+wallPath);
}

function storedFile(url){
	if(!uploadPath) return null;
	const name=path.basename(new URL(url).pathname);
	const out=[];
	(function walk(d){
		for(const e of fs.readdirSync(d, {withFileTypes: true})){
			const p=path.join(d, e.name);
			if(e.isDirectory()) walk(p); else if(e.name==name) out.push(p);
		}
	})(uploadPath);
	return out[0] || null;
}

(async()=>{
	const {dir, f}=makeFixtures();
	console.log('fixtures in '+dir);
	const browser=await chromium.launch();
	const page=await (await browser.newContext({viewport: {width: 1280, height: 800}})).newPage();
	page.on('pageerror', e=>{console.log('PAGE ERROR '+e.message); failures++;});

	await new Promise(r=>setTimeout(r, 1500));
	await page.goto(base+'/account/login');
	await page.fill('input[name=username]', login);
	await page.fill('input[name=password]', password);
	await Promise.all([page.waitForURL(u=>!/login/.test(u.pathname)), page.click('form button, form input[type=submit]')]);
	// /users/<id> redirects to the profile of the logged-in user
	const uid=await page.evaluate(()=>userConfig.uid);
	await page.goto(base+'/users/'+uid);
	const profileHref=new URL(page.url()).pathname;
	check(/^\/[A-Za-z0-9_]+$/.test(profileHref), 'found the profile: '+profileHref);

	// --- pages load
	for(const p of [profileHref, '/feed', '/settings', '/my/albums', '/my/mail', '/my/groups', '/my/friends', '/my/notifications']){
		const r=await page.goto(base+p);
		check(r.status()==200 && !/Internal Server Error/.test(await page.content()), 'page '+p+' loads');
	}

	// --- still photo attachment
	let r=await upload(page, '/system/upload/postPhoto', 'file', f('big.jpg'), 'big.jpg', 'image/jpeg');
	check(r.status==200, 'photo upload: '+r.status);
	let photo=JSON.parse(r.body);
	check(photo.kind=='still_image' && photo.width==2560 && photo.height==853, 'photo scaled to 2560: '+photo.width+'x'+photo.height);
	check(/\/i\//.test(photo.thumbs.webp) && photo.pv && photo.pv.urls.length>1, 'photo thumbnails through imgproxy with viewer sizes');
	await postWithAttachment(page, profileHref, 'Photo post', photo.id);
	let tile=await page.$eval('.postAttachments a.photo picture', el=>({sources: el.querySelectorAll('source').length, src: el.querySelector('img').getAttribute('src')}));
	check(tile.sources==2 && /\/i\//.test(tile.src), 'photo post renders a <picture> with WebP and JPEG sources');

	// --- animated GIF attachment
	r=await upload(page, '/system/upload/postPhoto', 'file', f('anim.gif'), 'anim.gif', 'image/gif');
	check(r.status==200, 'animated GIF upload: '+r.status+' '+r.body.slice(0, 100));
	let gif=JSON.parse(r.body);
	check(gif.kind=='animated_image' && gif.width==300 && gif.height==200, 'animated GIF kind and size: '+gif.kind+' '+gif.width+'x'+gif.height);
	check(gif.thumbs.webp==gif.thumbs.jpeg && /\.webp$/.test(gif.thumbs.webp) && !/\/i\//.test(gif.thumbs.webp), 'animated GIF is served as the stored file, not through imgproxy');
	const gifFile=storedFile(gif.thumbs.webp);
	if(uploadPath) check(gifFile && fs.statSync(gifFile).size>1000, 'animated WebP stored on disk: '+gifFile);
	await postWithAttachment(page, profileHref, 'GIF post', gif.id);
	tile=await page.$eval('.postAttachments a.photo picture', el=>({sources: el.querySelectorAll('source').length, src: el.querySelector('img').getAttribute('src')}));
	check(tile.sources==0 && tile.src==gif.thumbs.webp, 'GIF post renders a plain <img> of the stored file');
	r=await upload(page, '/system/upload/postPhoto', 'file', f('huge.gif'), 'huge.gif', 'image/gif');
	const huge=JSON.parse(r.body);
	check(r.status==200 && huge.width<800 && huge.width>600, 'animation over the pixel budget is scaled down: '+huge.width);
	r=await upload(page, '/system/upload/postPhoto', 'file', f('still.gif'), 'still.gif', 'image/gif');
	check(r.status==200 && JSON.parse(r.body).kind=='still_image', 'a still GIF is a still image');

	// --- video attachment
	const cfg=await page.evaluate(()=>userConfig.uploads);
	check(cfg && cfg.videoEnabled, 'server advertises video uploads: '+JSON.stringify(cfg));
	r=await upload(page, '/system/upload/postPhoto', 'file', f('clip.mp4'), 'clip.mp4', 'video/mp4');
	check(r.status==200, 'video upload: '+r.status+' '+r.body.slice(0, 120));
	const video=JSON.parse(r.body);
	check(video.kind=='video' && video.width==960 && video.height==720 && /\/i\//.test(video.thumbs.jpeg) && !video.pv, 'video is scaled to 720p and has a poster thumbnail: '+video.width+'x'+video.height);
	await postWithAttachment(page, profileHref, 'Video post', video.id);
	const placeholder=await page.$eval('.postAttachments .videoAttachment', el=>({id: el.dataset.mediaFileId, processing: el.classList.contains('videoProcessing'), player: !!el.querySelector('video')}));
	check(!!placeholder.id, 'video post renders a tile (processing='+placeholder.processing+', player='+placeholder.player+')');
	let ready=placeholder.player;
	for(let i=0;i<90 && !ready;i++){
		await page.waitForTimeout(1000);
		ready=await page.$eval('.postAttachments .videoAttachment', el=>!!el.querySelector('video')).catch(()=>false);
	}
	check(ready, 'placeholder became a player without reloading (websocket or polling)');
	await page.reload();
	const player=await page.$eval('.postAttachments .videoAttachment video', el=>({w: el.getAttribute('width'), h: el.getAttribute('height'), poster: el.getAttribute('poster'), src: el.querySelector('source').getAttribute('src')}));
	check(player.w=='960' && player.h=='720' && /\.mp4$/.test(player.src) && /\/i\//.test(player.poster), 'player markup after reload');
	const mp4=storedFile(player.src);
	if(uploadPath) check(mp4 && fs.statSync(mp4).size>10000, 'transcoded MP4 stored on disk: '+mp4);
	if(uploadPath) check(fs.readdirSync(path.join(uploadPath, 'spool')).length==0, 'spool directory is empty');
	r=await page.evaluate(async id=>{const r=await fetch('/system/mediaStatus?ids='+id); return {status: r.status, body: await r.text()};}, placeholder.id);
	check(r.status==200 && /<video/.test(r.body), 'status endpoint returns the player for a finished video');
	r=await upload(page, '/system/upload/messagePhoto', 'file', f('clip.mp4'), 'clip.mp4', 'video/mp4');
	check(r.status==200 && JSON.parse(r.body).kind=='video', 'video as a message attachment');
	r=await upload(page, '/system/upload/postPhoto', 'file', f('clip.mp4'), 'clip.jpg', 'image/jpeg');
	check(r.status==200 && JSON.parse(r.body).kind=='video', 'video declared as an image is detected as a video');
	r=await upload(page, '/system/upload/postPhoto', 'file', f('garbage.mp4'), 'garbage.mp4', 'video/mp4');
	check(r.status==400, 'garbage declared as video is refused: '+r.status);

	// --- albums
	await page.goto(base+'/my/albums/create');
	await page.fill('input[name=title]', 'Regression album');
	await Promise.all([page.waitForNavigation(), page.click('form input[type=submit], form button[type=submit]')]);
	const albumID=page.url().split('/').pop();
	r=await upload(page, '/albums/'+albumID+'/upload', 'file', f('anim.gif'), 'anim.gif', 'image/gif');
	const albumGif=JSON.parse(r.body);
	check(r.status==200 && albumGif.canRotate===false, 'animated album photo cannot be rotated');
	r=await upload(page, '/albums/'+albumID+'/upload', 'file', f('still.png'), 'still.png', 'image/png');
	const albumStill=JSON.parse(r.body);
	check(r.status==200 && albumStill.canRotate===true, 'still album photo can be rotated');
	r=await page.evaluate(async id=>(await fetch('/photos/'+id+'/rotate?cw&from=edit&_ajax=1&csrf='+userConfig.csrf)).status, albumStill.id);
	check(r==200, 'rotating the still photo works: '+r);
	r=await page.evaluate(async id=>(await fetch('/photos/'+id+'/rotate?cw&from=edit&_ajax=1&csrf='+userConfig.csrf)).status, albumGif.id);
	check(r==400, 'rotating the animated photo is refused: '+r);
	await page.goto(base+'/albums/'+albumID+'/edit');
	const rotateIDs=await page.$$eval('.rotateButtons', els=>els.map(e=>e.id));
	check(rotateIDs.length==1 && rotateIDs[0]=='photoRotateButtons_'+albumStill.id, 'album editor shows rotate buttons only for the still photo');
	r=await upload(page, '/albums/'+albumID+'/upload', 'file', f('clip.mp4'), 'clip.mp4', 'video/mp4');
	check(r.status==415, 'album refuses a video: '+r.status);

	// --- avatars and logos
	const avatarURL='/settings/updateProfilePicture?rotation=0&crop=0,0,1,1,0,0,1,1';
	r=await upload(page, avatarURL, 'file', f('anim.gif'), 'anim.gif', 'image/gif');
	check(r.status==415, 'animated avatar is refused: '+r.status);
	r=await upload(page, avatarURL, 'file', f('clip.mp4'), 'clip.mp4', 'video/mp4');
	check(r.status==415, 'video avatar is refused: '+r.status);
	r=await upload(page, avatarURL, 'file', f('still.gif'), 'still.gif', 'image/gif');
	check(r.status==200, 'still GIF avatar is accepted: '+r.status);
	await page.goto(base+profileHref);
	const ava=await page.$eval('.ava img, .ava picture img', i=>i.getAttribute('src')).catch(()=>null);
	check(ava && /\/i\//.test(ava), 'profile shows the avatar through imgproxy');
	await page.goto(base+'/my/groups/create');
	await page.fill('input[name=name]', 'Regression group');
	await Promise.all([page.waitForNavigation(), page.click('form input[type=submit], form button[type=submit]')]);
	const gid=(await page.content()).match(/\/groups\/(\d+)\//);
	if(gid){
		r=await upload(page, avatarURL+'&group='+gid[1], 'file', f('logo.png'), 'logo.png', 'image/png');
		check(r.status==200, 'group logo upload: '+r.status);
		await page.goto(base+'/groups/'+gid[1]);
		const gAva=await page.$eval('.ava img, .ava picture img', i=>i.getAttribute('src')).catch(()=>null);
		check(gAva && /\/i\//.test(gAva), 'group page shows its logo');
	}else{
		check(false, 'group created');
	}
	await page.goto(base+'/apps/create');
	r=await upload(page, '/apps/uploadLogo', 'file', f('logo.png'), 'logo.png', 'image/png');
	check(r.status==200, 'app logo upload: '+r.status);
	r=await upload(page, '/apps/uploadLogo', 'file', f('still.png'), 'still.png', 'image/png');
	check(r.status==422, 'non-square app logo is refused: '+r.status);
	r=await upload(page, '/apps/uploadLogo', 'file', f('anim.gif'), 'anim.gif', 'image/gif');
	check(r.status==415, 'animated app logo is refused: '+r.status);

	await browser.close();
	fs.rmSync(dir, {recursive: true, force: true});
	console.log(failures==0 ? 'ALL PASSED' : failures+' FAILED');
	process.exit(failures==0 ? 0 : 1);
})().catch(e=>{console.error(e); process.exit(1);});
