/*
 * Minimal string table for the web dashboard.
 *
 * Deliberately not a framework: stage 1 ships a handful of strings, and the game already has its own
 * localisation that the page cannot read (the classpath assets are not exposed over HTTP). Keeping
 * the keys aligned with the in-game translation keys makes it easy to cross-check wording later.
 *
 * A key missing from the chosen language falls back to English, then to the key itself, so a
 * half-translated table is never a blank page.
 */

const ENGLISH = {
	title: 'Web Dashboard',
	subtitle: 'Minecraft Transit Railway',
	statusLoading: 'Checking service…',
	statusReady: 'Service online',
	statusOffline: 'Service unreachable',
	messageLoading: 'Loading…',
	messageOffline: 'Could not reach the web dashboard service. Make sure the game is running and the port is correct.',
	messageNoEdit: 'You are not signed in, so you can only view. Open this dashboard from the in-game dashboard button to sign in.',
	versionLabel: 'Mod version',
	portLabel: 'Port',
	readOnlyNote: 'This page is not signed in, so it is read-only. Open the dashboard from inside the game to sign in.',
	stageNote: 'Stage 1: entry point and service only. Data editing arrives in a later stage.'
};

const SIMPLIFIED_CHINESE = {
	title: '网页仪表板',
	subtitle: 'Minecraft Transit Railway',
	statusLoading: '正在检查服务…',
	statusReady: '服务已连接',
	statusOffline: '无法连接服务',
	messageLoading: '加载中…',
	messageOffline: '无法连接到网页仪表板服务。请确认游戏正在运行，且端口正确。',
	messageNoEdit: '你尚未登录，只能查看。请从游戏内仪表板按钮打开本页面以登录。',
	versionLabel: '模组版本',
	portLabel: '端口',
	readOnlyNote: '本页面尚未登录，因此为只读模式。请从游戏内打开以登录。',
	stageNote: '阶段 1：仅入口与服务。数据编辑将在后续阶段提供。'
};

const TRADITIONAL_CHINESE = {
	title: '網頁儀表板',
	subtitle: 'Minecraft Transit Railway',
	statusLoading: '正在檢查服務…',
	statusReady: '服務已連線',
	statusOffline: '無法連線服務',
	messageLoading: '載入中…',
	messageOffline: '無法連線到網頁儀表板服務。請確認遊戲正在執行，且連接埠正確。',
	messageNoEdit: '你尚未登入，只能檢視。請從遊戲內儀表板按鈕開啟本頁面以登入。',
	versionLabel: '模組版本',
	portLabel: '連接埠',
	readOnlyNote: '本頁面尚未登入，因此為唯讀模式。請從遊戲內開啟以登入。',
	stageNote: '階段 1：僅入口與服務。資料編輯將於後續階段提供。'
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

		// Exact region match first, so zh-Hant-HK and zh-Hans-CN land on the right table.
		const script = /hant/.test(tag) ? 'zh-tw' : /hans/.test(tag) ? 'zh-cn' : null;
		if (script) {
			return script;
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
