/*
 * Minimal string table for the web dashboard.
 *
 * Deliberately not a framework: the page ships a handful of strings, and the game's own localisation
 * cannot be reused because those files are not exposed over HTTP. Keys are named after the in-game
 * keys where the wording matches, to make cross-checking easy.
 *
 * A key missing from the chosen language falls back to English, then to the key itself, so a
 * half-translated table is never a blank page.
 */

const ENGLISH = {
	title: 'Web Dashboard',
	subtitle: 'Minecraft Transit Railway',
	statusLoading: 'Checking service…',
	statusReady: 'Service online',
	statusReadOnly: 'Read-only',
	statusOffline: 'Service unreachable',
	versionLabel: 'Mod version',
	portLabel: 'Port',
	readOnlyHeadline: 'You cannot edit',
	readOnlyBody: 'Open this dashboard from the in-game Web Dashboard button to sign in, or ask an operator to grant you access.',
	signedInBody: 'You are signed in and may edit the railway data.',
	signOut: 'Sign out',
	offlineHeadline: 'Service unreachable',
	offlineBody: 'Could not reach the web dashboard service. Make sure the game is running and the port is right.',
	expiredHeadline: 'Sign-in expired',
	expiredBody: 'Press the Web Dashboard button in the game again to get a fresh link.',
	lanWarning: 'This service is open to the local network over plain HTTP, so sign-in tokens can be read by anyone on the same network. Restart with allowLanAccess turned off to avoid that.',
	stageNote: 'Data management arrives in the next stage.',
	stageFootnote: 'Stage 2: sign-in and permissions only.'
};

const SIMPLIFIED_CHINESE = {
	title: '网页仪表板',
	subtitle: 'Minecraft Transit Railway',
	statusLoading: '正在检查服务…',
	statusReady: '服务已连接',
	statusReadOnly: '只读',
	statusOffline: '无法连接服务',
	versionLabel: '模组版本',
	portLabel: '端口',
	readOnlyHeadline: '你无法编辑',
	readOnlyBody: '请从游戏内的「网页仪表板」按钮进入以登录，或联系管理员授予你权限。',
	signedInBody: '你已登录，可以编辑铁路数据。',
	signOut: '登出',
	offlineHeadline: '无法连接服务',
	offlineBody: '无法连接到网页仪表板服务。请确认游戏正在运行，且端口正确。',
	expiredHeadline: '登录已过期',
	expiredBody: '请在游戏中重新点击「网页仪表板」按钮以获取新的链接。',
	lanWarning: '本服务以明文 HTTP 开放到局域网，同网段的人可以读取登录令牌。若不需要，请把 allowLanAccess 关闭后重启。',
	stageNote: '数据管理将在下一阶段提供。',
	stageFootnote: '阶段 2：仅登录与权限。'
};

const TRADITIONAL_CHINESE = {
	title: '網頁儀表板',
	subtitle: 'Minecraft Transit Railway',
	statusLoading: '正在檢查服務…',
	statusReady: '服務已連線',
	statusReadOnly: '唯讀',
	statusOffline: '無法連線服務',
	versionLabel: '模組版本',
	portLabel: '連接埠',
	readOnlyHeadline: '你無法編輯',
	readOnlyBody: '請從遊戲內的「網頁儀表板」按鈕進入以登入，或聯絡管理員授予你權限。',
	signedInBody: '你已登入，可以編輯鐵路資料。',
	signOut: '登出',
	offlineHeadline: '無法連線服務',
	offlineBody: '無法連線到網頁儀表板服務。請確認遊戲正在執行，且連接埠正確。',
	expiredHeadline: '登入已過期',
	expiredBody: '請在遊戲中重新點擊「網頁儀表板」按鈕以取得新的連結。',
	lanWarning: '本服務以明文 HTTP 開放至區域網路，同網段的人可以讀取登入權杖。若不需要，請將 allowLanAccess 關閉後重新啟動。',
	stageNote: '資料管理將於下一階段提供。',
	stageFootnote: '階段 2：僅登入與權限。'
};

const TABLES = {
	en: ENGLISH,
	'zh-cn': SIMPLIFIED_CHINESE,
	'zh-sg': SIMPLIFIED_CHINESE,
	'zh-tw': TRADITIONAL_CHINESE,
	'zh-hk': TRADITIONAL_CHINESE,
	'zh-mo': TRADITIONAL_CHINESE
};

/** Resolves the browser's preferred language to one of the tables above. */
function resolveLanguage() {
	const candidates = navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language || 'en'];

	for (const candidate of candidates) {
		const tag = String(candidate).toLowerCase();

		// Exact script match first, so zh-Hant-HK and zh-Hans-CN land on the right table.
		if (/hant/.test(tag)) {
			return 'zh-tw';
		}
		if (/hans/.test(tag)) {
			return 'zh-cn';
		}

		const region = tag.split('-').slice(0, 2).join('-');
		if (TABLES[region]) {
			return region;
		}

		const language = tag.split('-')[0];
		if (language === 'zh') {
			return 'zh-cn';
		}
		if (TABLES[language]) {
			return language;
		}
	}

	return 'en';
}

const language = resolveLanguage();
const table = TABLES[language];
document.documentElement.lang = language;

/**
 * @param {string} key a key of the English table.
 * @returns {string} the translated string, or the key when it is unknown.
 */
export function t(key) {
	const value = table[key];
	if (typeof value === 'string') {
		return value;
	}
	return typeof ENGLISH[key] === 'string' ? ENGLISH[key] : key;
}

/**
 * Replaces the text of every element carrying {@code data-i18n}, so the markup stays declarative and
 * this module stays free of DOM knowledge about individual elements.
 */
export function applyTranslations(root) {
	root.querySelectorAll('[data-i18n]').forEach(element => {
		element.textContent = t(element.dataset.i18n);
	});
}
