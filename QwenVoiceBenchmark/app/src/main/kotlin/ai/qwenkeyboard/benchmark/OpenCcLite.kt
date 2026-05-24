package ai.qwenkeyboard.benchmark

/**
 * Small bundled OpenCC-style phrase converter for offline keyboard use.
 *
 * This is phrase-first, longest-match conversion, then the IME's existing
 * character fallback handles remaining characters. It is intentionally local
 * and dependency-free for the Android IME.
 */
object OpenCcLite {
    private val simpToTradPhrases = linkedMapOf(
        "人工智能" to "人工智能",

        "吃饭" to "食飯",
        "食饭" to "食飯",
        "饭" to "飯",
        "还没" to "還未",
        "没" to "冇",
        "点解" to "點解",
        "未啊" to "未啊",
        "中文" to "中文",
        "简体中文" to "簡體中文",
        "繁体中文" to "繁體中文",
        "选择" to "選擇",
        "传统" to "傳統",
        "这里" to "這裏",
        "这边" to "這邊",
        "边度" to "邊度",
        "电话" to "電話",
        "信息" to "訊息",
        "消息" to "訊息",
        "发送" to "發送",
        "回复" to "回覆",
        "之后" to "之後",
        "之前" to "之前",
        "还有" to "還有",
        "还有没有" to "還有冇",
        "应该" to "應該",
        "问题" to "問題",
        "处理" to "處理",
        "发生" to "發生",
        "发觉" to "發覺",
        "发表" to "發表",
        "发出" to "發出",
        "开发" to "開發",
        "开发商" to "開發商",
        "头先" to "頭先",
        "老板" to "老闆",
        "干净" to "乾淨",
        "干线" to "幹線",
        "联系" to "聯絡",
        "里面" to "裏面",
        "为了" to "為咗",
        "会计师" to "會計師",
        "会计" to "會計",
        "软件" to "軟件",
        "应用程序" to "應用程式",
        "应用" to "應用",
        "程序" to "程式",
        "计算机" to "電腦",
        "电脑" to "電腦",
        "网络" to "網絡",
        "服务器" to "伺服器",
        "数据库" to "資料庫",
        "数据" to "數據",
        "项目" to "項目",
        "测试" to "測試",
        "语音" to "語音",
        "识别" to "識別",
        "输入法" to "輸入法",
        "键盘" to "鍵盤",
        "上传" to "上載",
        "下载" to "下載",
        "云端" to "雲端",
        "文件" to "文件",
        "视频" to "影片",
        "默认" to "預設",
        "设置" to "設定",
        "用户" to "用戶",
        "账号" to "帳號",
        "账户" to "帳戶",
        "质量" to "質素",
        "里面" to "裏面",
        "这里" to "這裏",
        "那里" to "那裏",
        "老板" to "老闆",
        "干什么" to "幹甚麼",
        "什么" to "甚麼",
        "为什么" to "為甚麼",
        "发布" to "發布",
        "头发" to "頭髮",
        "发展" to "發展",
        "发现" to "發現",
        "只不过" to "只不過",
        "因为" to "因為",
        "但是" to "但是",
        "这个" to "這個",
        "那个" to "那個",
        "这些" to "這些",
        "那些" to "那些",
        "他们" to "他們",
        "我们" to "我們",
        "你们" to "你們",
        "广东话" to "廣東話",
        "粤语" to "粵語",
        "简体" to "簡體",
        "繁体" to "繁體"
    )

    private val tradToSimpPhrases = simpToTradPhrases.entries
        .associate { (simp, trad) -> trad to simp }
        .toList()
        .sortedByDescending { it.first.length }

    private val simpToTradSorted = simpToTradPhrases.toList().sortedByDescending { it.first.length }

    fun toTraditional(text: String): String = replacePhrases(text, simpToTradSorted)
    fun toSimplified(text: String): String = replacePhrases(text, tradToSimpPhrases)

    private fun replacePhrases(text: String, phrases: List<Pair<String, String>>): String {
        var out = text
        for ((from, to) in phrases) out = out.replace(from, to)
        return out
    }
}
