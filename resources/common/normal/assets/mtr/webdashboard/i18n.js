/*
 * Minimal string table for the web dashboard.
 *
 * Deliberately not a framework: the page ships a manageable number of strings, and the game's own
 * localisation cannot be reused because those files are not exposed over HTTP. Keys are named after
 * the in-game keys where the wording matches, to make cross-checking easy.
 *
 * A key missing from the chosen language falls back to English, then to the key itself, so a
 * half-translated table is never a blank page.
 */

const ENGLISH = {
	title: 'Web Dashboard',
	subtitle: 'Yomi\'s Minecraft Transit Railway',
	statusLoading: 'Checking service…',
	statusReady: 'Service online',
	statusReadOnly: 'Read-only',
	statusOffline: 'Service unreachable',
	versionLabel: 'YMTR version',
	portLabel: 'Port',
	readOnlyHeadline: 'No editing permissions',
	readOnlyBody: 'Open this dashboard from the in-game Web Dashboard button to sign in, or ask an operator to grant you access.',
	signedInBody: 'You are signed in and may edit the railway data.',
	signOut: 'Sign out',
	signOutFailed: 'Sign-out failed, try again',
	offlineHeadline: 'Service unreachable',
	offlineBody: 'Could not reach the web dashboard service. Make sure the game is running and the port is right.',
	expiredHeadline: 'Sign-in expired',
	expiredBody: 'Press the Web Dashboard button in the game again to get a fresh link.',
	lanWarning: 'This service is open to the local network over plain HTTP, so sign-in tokens can be read by anyone on the same network. Restart with allowLanAccess turned off to avoid that.',
	stageNote: 'More editing features are coming in future updates.',
	stageFootnote: 'Thanks for using the YMTR mod.',

	// Filter chips. "All" has no equivalent in the game, which shows one mode per dashboard.
	modeAll: 'All',
	modeTRAIN: 'Rail',
	modeBOAT: 'Ferry',
	modeCABLE_CAR: 'Cable car',
	modeAIRPLANE: 'Airplane',

	// Sidebar navigation, matching the game's three tabs.
	tabStations: 'Stations',
	tabRoutes: 'Routes',
	tabDepots: 'Depots',

	searchPlaceholder: 'Search',
	clearSearch: 'Clear search',
	refresh: 'Refresh',
	// Accessible names for regions that have no visible text of their own. Kept next to the other
	// navigation strings so a translator meets them together. Where the visible label and the spoken
	// label would be the same string, the key is still declared separately: sharing one key between the
	// title and the aria-label couples two things that a translator may legitimately want to word
	// differently for a screen reader.
	refreshAriaLabel: 'Refresh',
	searchAriaLabel: 'Search railway data',
	sidebarLabel: 'Railway data',
	listLabel: 'Railway data list',
	mapLabel: 'Map',
	readOnlyBadge: 'Read-only',
	editableBadge: 'Can edit',

	// Row summaries.
	summaryPlatforms: '%s platforms',
	summarySidings: '%s sidings',
	summaryStops: '%s stops',
	summaryRoutes: '%s routes',
	summaryZone: 'Fare zone %s',
	untitled: 'Untitled',

	// Empty states, one per cause so a blank list is never ambiguous.
	emptyStations: 'No stations yet',
	emptyRoutes: 'No routes yet',
	emptyDepots: 'No depots yet',
	noResults: 'Nothing matches “%s”',
	loadingData: 'Reading railway data…',
	dataUnavailable: 'No world is loaded',
	dataUnavailableBody: 'The game has no world open, so there is nothing to show yet.',
	dataFailed: 'Could not read the railway data.',
	mapZoomIn: 'Zoom in',
	mapZoomOut: 'Zoom out',
	mapFocusPlayer: 'Focus on player',
	mapEditingHint: 'Drag on the map to draw the new area',
	mapDraftSize: '%s × %s blocks',
	mapCancelEdit: 'Cancel',
	mapSaveEdit: 'Save',
	editButton: 'Edit',
	editRowLabel: 'Edit %s',
	editStationTitle: 'Edit station',
	editRouteTitle: 'Edit route',
	editDepotTitle: 'Edit depot',
	editClose: 'Close',
	editCancel: 'Cancel',
	editSave: 'Save',
	editSaving: 'Saving',
	editFixFields: 'Please correct the highlighted fields',
	editInvalidField: 'The server rejected this value',
	editSaveFailed: 'Could not save. Please try again',
	editEditorOffline: 'You have left the game, so this change cannot be recorded. Rejoin and sign in again to save.',
	editObjectGone: 'This was deleted in the game. Refresh to update the list.',
	editNotPermitted: 'Your account is not allowed to edit',
	editSessionExpired: 'Your sign-in has expired. Sign in again from the game.',
	editNoServer: 'No world is open, so there is nothing to save to',
	fieldName: 'Name',
	fieldColor: 'Colour',
	fieldCorners: 'Area',
	editRedrawArea: 'Redraw on the map',
	errorSelectionNotStorable: 'This area cannot be saved',
	colorSaturationValue: 'Saturation and brightness',
	colorHue: 'Hue',
	fieldZone: 'Fare zone',
	errorNameTooLong: 'This name is too long',
	errorWholeNumber: 'Enter a whole number',
	errorTooSmall: 'This number is too small',
	errorTooLarge: 'This number is too large',
	errorColorRange: 'Pick a colour from the swatches or type a hex value',
	errorUnsupportedField: 'This build cannot edit that field'
};

const SIMPLIFIED_CHINESE = {
	title: '网页仪表板',
	subtitle: 'YMTR',
	statusLoading: '正在检查服务…',
	statusReady: '服务已连接',
	statusReadOnly: '只读',
	statusOffline: '无法连接服务',
	versionLabel: 'YMTR版本',
	portLabel: '端口',
	readOnlyHeadline: '无编辑权限',
	readOnlyBody: '请从游戏内的「网页仪表板」按钮进入以登录，或联系管理员授予你权限。',
	signedInBody: '你已登录，可以编辑铁路数据。',
	signOut: '登出',
	signOutFailed: '登出失败，请重试',
	offlineHeadline: '无法连接服务',
	offlineBody: '无法连接到网页仪表板服务。请确认游戏正在运行，且端口正确。',
	expiredHeadline: '登录已过期',
	expiredBody: '请在游戏中重新点击「网页仪表板」按钮以获取新的链接。',
	lanWarning: '本服务以明文 HTTP 开放到局域网，同网段的人可以读取登录令牌。若不需要，请把 allowLanAccess 关闭后重启。',
	stageNote: '更多编辑功能请等待后续更新',
	stageFootnote: '感谢您使用YMTR模组',

	modeAll: '全部',
	modeTRAIN: '轨交',
	modeBOAT: '轮渡',
	modeCABLE_CAR: '缆车',
	modeAIRPLANE: '飞机',

	tabStations: '车站',
	tabRoutes: '路线',
	tabDepots: '车厂',

	searchPlaceholder: '搜索',
	clearSearch: '清空搜索',
	refresh: '刷新',
	refreshAriaLabel: '刷新',
	searchAriaLabel: '搜索铁路数据',
	sidebarLabel: '铁路数据',
	listLabel: '铁路数据列表',
	mapLabel: '地图',
	readOnlyBadge: '只读',
	editableBadge: '可编辑',

	summaryPlatforms: '%s 个站台',
	summarySidings: '%s 条侧线',
	summaryStops: '%s 站',
	summaryRoutes: '%s 条路线',
	summaryZone: '收费区 %s',
	untitled: '未命名',

	emptyStations: '还没有车站',
	emptyRoutes: '还没有路线',
	emptyDepots: '还没有车厂',
	noResults: '没有匹配「%s」的条目',
	loadingData: '正在读取铁路数据…',
	dataUnavailable: '未加载世界',
	dataUnavailableBody: '游戏当前没有打开任何世界，暂时没有可显示的数据。',
	dataFailed: '读取铁路数据失败。',
	mapZoomIn: '放大',
	mapZoomOut: '缩小',
	mapFocusPlayer: '聚焦到玩家',
	mapEditingHint: '在地图上拖动以绘制新的区域',
	mapDraftSize: '%s × %s 格',
	mapCancelEdit: '取消',
	mapSaveEdit: '保存',
	editButton: '编辑',
	editRowLabel: '编辑 %s',
	editStationTitle: '编辑车站',
	editRouteTitle: '编辑路线',
	editDepotTitle: '编辑车厂',
	editClose: '关闭',
	editCancel: '取消',
	editSave: '保存',
	editSaving: '正在保存',
	editFixFields: '请修正标出的字段',
	editInvalidField: '服务器拒绝了该值',
	editSaveFailed: '保存失败，请重试',
	editEditorOffline: '你已离开游戏，这次改动无法记录。请重新进入游戏并登录后再保存。',
	editObjectGone: '该对象已在游戏内被删除。刷新以更新列表。',
	editNotPermitted: '你的账号没有编辑权限',
	editSessionExpired: '登录已过期，请从游戏内重新登录。',
	editNoServer: '当前没有打开的世界，无处可保存',
	fieldName: '名称',
	fieldColor: '颜色',
	fieldCorners: '区域',
	editRedrawArea: '在地图上重划',
	errorSelectionNotStorable: '该区域无法保存',
	colorSaturationValue: '饱和度与明度',
	colorHue: '色相',
	fieldZone: '收费区',
	errorNameTooLong: '名称过长',
	errorWholeNumber: '请输入整数',
	errorTooSmall: '数值过小',
	errorTooLarge: '数值过大',
	errorColorRange: '请从色板中选色，或输入十六进制值',
	errorUnsupportedField: '当前版本无法编辑该字段'
};

const TRADITIONAL_CHINESE = {
	title: '網頁儀表板',
	subtitle: 'YMTR',
	statusLoading: '正在檢查服務…',
	statusReady: '服務已連線',
	statusReadOnly: '唯讀',
	statusOffline: '無法連線服務',
	versionLabel: 'YMTR版本',
	portLabel: '連接埠',
	readOnlyHeadline: '無編輯權限',
	readOnlyBody: '請從遊戲內的「網頁儀表板」按鈕進入以登入，或聯絡管理員授予你權限。',
	signedInBody: '你已登入，可以編輯鐵路資料。',
	signOut: '登出',
	signOutFailed: '登出失敗，請重試',
	offlineHeadline: '無法連線服務',
	offlineBody: '無法連線到網頁儀表板服務。請確認遊戲正在執行，且連接埠正確。',
	expiredHeadline: '登入已過期',
	expiredBody: '請在遊戲中重新點擊「網頁儀表板」按鈕以取得新的連結。',
	lanWarning: '本服務以明文 HTTP 開放至區域網路，同網段的人可以讀取登入權杖。若不需要，請將 allowLanAccess 關閉後重新啟動。',
	stageNote: '更多編輯功能請等待後續更新',
	stageFootnote: '感謝您使用YMTR模組',

	modeAll: '全部',
	modeTRAIN: '軌道交通',
	modeBOAT: '渡輪',
	modeCABLE_CAR: '纜車',
	modeAIRPLANE: '飛機',

	tabStations: '車站',
	tabRoutes: '路線',
	tabDepots: '車廠',

	searchPlaceholder: '搜尋',
	clearSearch: '清除搜尋',
	refresh: '重新整理',
	refreshAriaLabel: '重新整理',
	searchAriaLabel: '搜尋鐵路資料',
	sidebarLabel: '鐵路資料',
	listLabel: '鐵路資料列表',
	mapLabel: '地圖',
	readOnlyBadge: '唯讀',
	editableBadge: '可編輯',

	summaryPlatforms: '%s 個月台',
	summarySidings: '%s 條側線',
	summaryStops: '%s 站',
	summaryRoutes: '%s 條路線',
	summaryZone: '收費區 %s',
	untitled: '未命名',

	emptyStations: '還沒有車站',
	emptyRoutes: '還沒有路線',
	emptyDepots: '還沒有車廠',
	noResults: '沒有符合「%s」的項目',
	loadingData: '正在讀取鐵路資料…',
	dataUnavailable: '未載入世界',
	dataUnavailableBody: '遊戲目前沒有開啟任何世界，暫無可顯示的資料。',
	dataFailed: '讀取鐵路資料失敗。',
	mapZoomIn: '放大',
	mapZoomOut: '縮小',
	mapFocusPlayer: '聚焦到玩家',
	mapEditingHint: '在地圖上拖曳以繪製新的區域',
	mapDraftSize: '%s × %s 格',
	mapCancelEdit: '取消',
	mapSaveEdit: '儲存',
	editButton: '編輯',
	editRowLabel: '編輯 %s',
	editStationTitle: '編輯車站',
	editRouteTitle: '編輯路線',
	editDepotTitle: '編輯車廠',
	editClose: '關閉',
	editCancel: '取消',
	editSave: '儲存',
	editSaving: '正在儲存',
	editFixFields: '請修正標出的欄位',
	editInvalidField: '伺服器拒絕了該值',
	editSaveFailed: '儲存失敗，請重試',
	editEditorOffline: '你已離開遊戲，這次變更無法記錄。請重新進入遊戲並登入後再儲存。',
	editObjectGone: '該物件已在遊戲內被刪除。重新整理以更新列表。',
	editNotPermitted: '你的帳號沒有編輯權限',
	editSessionExpired: '登入已過期，請從遊戲內重新登入。',
	editNoServer: '目前沒有開啟的世界，無處可儲存',
	fieldName: '名稱',
	fieldColor: '顏色',
	fieldCorners: '區域',
	editRedrawArea: '在地圖上重劃',
	errorSelectionNotStorable: '該區域無法儲存',
	colorSaturationValue: '飽和度與明度',
	colorHue: '色相',
	fieldZone: '收費區',
	errorNameTooLong: '名稱過長',
	errorWholeNumber: '請輸入整數',
	errorTooSmall: '數值過小',
	errorTooLarge: '數值過大',
	errorColorRange: '請從色板中選色，或輸入十六進位值',
	errorUnsupportedField: '目前版本無法編輯該欄位'
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
 * Looks up a string and substitutes its {@code %s} placeholders in order.
 *
 * Extra arguments are ignored and missing ones are left as a literal {@code %s} rather than becoming
 * "undefined", so a table that is short a placeholder degrades to something a translator can spot.
 *
 * @param {string} key
 * @param {...(string|number)} values
 * @returns {string}
 */
export function f(key, ...values) {
	let index = 0;
	return t(key).replace(/%s/g, () => (index < values.length ? String(values[index++]) : '%s'));
}

/**
 * Replaces the text of every element carrying {@code data-i18n}, so the markup stays declarative and
 * this module stays free of DOM knowledge about individual elements.
 * <p>
 * Also fills in the three attributes that carry user-visible text. {@code data-i18n-aria-label} exists
 * because an icon-only button has no text of its own: without it the accessible name would stay in
 * whatever language the markup was written in, so a Chinese reader would hear "Refresh" from a screen
 * reader while seeing a Chinese tooltip. That is worse than untranslated text, because it is
 * inconsistent between the two senses.
 */
export function applyTranslations(root) {
	root.querySelectorAll('[data-i18n]').forEach(element => {
		element.textContent = t(element.dataset.i18n);
	});
	root.querySelectorAll('[data-i18n-placeholder]').forEach(element => {
		element.placeholder = t(element.dataset.i18nPlaceholder);
	});
	root.querySelectorAll('[data-i18n-title]').forEach(element => {
		element.title = t(element.dataset.i18nTitle);
	});
	root.querySelectorAll('[data-i18n-aria-label]').forEach(element => {
		element.setAttribute('aria-label', t(element.dataset.i18nAriaLabel));
	});
}
