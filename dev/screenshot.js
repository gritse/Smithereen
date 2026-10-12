// Logs in and screenshots a few pages on desktop and mobile. See TESTS.md.
// Usage: NODE_PATH=<dir with playwright> node dev/screenshot.js [outputDir] [path ...]
const {chromium}=require('playwright');
const path=require('path');

const base=process.env.BASE_URL || 'http://127.0.0.1:4567';
const login=process.env.LOGIN || 'admin@example.com';
const password=process.env.PASSWORD || 'password';
const outDir=process.argv[2] || 'screenshots';
const pages=process.argv.length>3 ? process.argv.slice(3) : ['/my/mail', '/my/mail/outbox'];

const mobileUA='Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1';

(async()=>{
	const browser=await chromium.launch();
	for(const [name, opts] of [
		['desktop', {viewport: {width: 1280, height: 800}}],
		['mobile', {viewport: {width: 390, height: 844}, deviceScaleFactor: 2, isMobile: true, userAgent: mobileUA}],
	]){
		const ctx=await browser.newContext(opts);
		const page=await ctx.newPage();
		// Login is limited to one attempt per second per IP, and we log in once per layout
		await new Promise(r=>setTimeout(r, 1500));
		await page.goto(base+'/account/login');
		await page.fill('input[name=username]', login);
		await page.fill('input[name=password]', password);
		await Promise.all([page.waitForURL(u=>!/login/.test(u.pathname)), page.click('form button, form input[type=submit]')]);
		for(const p of pages){
			await page.goto(base+p);
			const file=path.join(outDir, name+'_'+(p.replace(/[^a-z0-9]+/gi, '_').replace(/^_|_$/g, '') || 'home')+'.png');
			await page.screenshot({path: file, fullPage: true});
			console.log(file, '-', await page.title());
		}
		await ctx.close();
	}
	await browser.close();
})().catch(e=>{console.error(e); process.exit(1);});
