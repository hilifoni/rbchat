package com.example.rbchat

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

class GroupActivity : Activity() {

    private class M(val id: String, val rid: Long, val uid: String, val name: String, val text: String, val game: String)

    private var gid = ""
    private var titleTv: TextView? = null
    private var pinBox: LinearLayout? = null
    private var list: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var input: EditText? = null
    private var msgs: List<M> = emptyList()
    private var reg: ListenerRegistration? = null
    private var grpReg: ListenerRegistration? = null
    private var owner = ""
    private var modsRaw: Any? = null
    private var perms: Set<String> = emptySet()
    private var banned: Map<String, String> = emptyMap()
    private var muted: Map<String, Long> = emptyMap()
    private var mutedReason: Map<String, String> = emptyMap()
    private var words: List<String> = emptyList()
    private var pins: List<Any?> = emptyList()
    private var pinGame: Map<*, *>? = null
    private val avatars = HashMap<Long, Bitmap>()
    private val asked = HashSet<Long>()
    private val verified = HashMap<Long, String>()
    private val hidden = HashSet<String>()
    private var seeded = false
    private var lastSend = 0L
    private var started = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    private fun me(): String = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    private fun lp(w: Int, h: Int, weight: Float): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        gid = getSharedPreferences("room", MODE_PRIVATE).getString("gid", "") ?: ""
        if (gid.isEmpty()) {
            toast("Сначала выбери группу")
            finish()
            return
        }
        val MP = ViewGroup.LayoutParams.MATCH_PARENT
        val WC = ViewGroup.LayoutParams.WRAP_CONTENT
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        val title = TextView(this)
        title.text = (getSharedPreferences("room", MODE_PRIVATE).getString("gname", "") ?: "") + "  ▾ участники"
        title.textSize = 18f
        title.setPadding(dp(14), dp(12), dp(14), dp(12))
        title.setBackgroundColor(Color.parseColor("#E0E0E0"))
        title.setOnClickListener { showMembers() }
        root.addView(title, LinearLayout.LayoutParams(MP, WC))
        titleTv = title

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        val inGame = Button(this)
        inGame.text = "▶ Чат в игре"
        inGame.isAllCaps = false
        inGame.setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            i.putExtra("autostart", true)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
        }
        bar.addView(inGame, lp(0, WC, 1f))
        val gameBtn = Button(this)
        gameBtn.text = "🎮 Игра"
        gameBtn.isAllCaps = false
        gameBtn.setOnClickListener { showGames() }
        bar.addView(gameBtn, lp(0, WC, 1f))
        root.addView(bar, LinearLayout.LayoutParams(MP, WC))

        val pb = LinearLayout(this)
        pb.orientation = LinearLayout.VERTICAL
        pb.setBackgroundColor(Color.parseColor("#FFF8E1"))
        pb.setPadding(dp(10), 0, dp(10), 0)
        root.addView(pb, LinearLayout.LayoutParams(MP, WC))
        pinBox = pb

        val sv = ScrollView(this)
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(10), dp(8), dp(10), dp(8))
        sv.addView(l, ViewGroup.LayoutParams(MP, WC))
        root.addView(sv, lp(MP, 0, 1f))
        list = l
        scroll = sv

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val et = EditText(this)
        et.hint = "Сообщение или команда (/mute, /ban)"
        et.setSingleLine(true)
        et.imeOptions = EditorInfo.IME_ACTION_SEND
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }
        row.addView(et, lp(0, WC, 1f))
        val b = Button(this)
        b.text = "Отпр."
        b.setOnClickListener { send() }
        row.addView(b, LinearLayout.LayoutParams(WC, WC))
        root.addView(row, LinearLayout.LayoutParams(MP, WC))
        input = et

        setContentView(root)

        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            begin()
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { begin() }
                .addOnFailureListener { toast("Вход не удался. Включи VPN и открой группу заново") }
        }
    }

    private fun begin() {
        if (started) return
        started = true
        GroupsUi.register(this, gid)
        val ref = FirebaseFirestore.getInstance().collection("groups").document(gid)
        grpReg = ref.addSnapshotListener { d, e ->
            if (e != null || d == null) return@addSnapshotListener
            if (!d.exists()) {
                toast("Группа удалена")
                getSharedPreferences("room", MODE_PRIVATE).edit().putString("gid", "").putString("gname", "").apply()
                finish()
                return@addSnapshotListener
            }
            titleTv?.text = (d.getString("name") ?: "Группа") + "  ▾ участники"
            owner = d.getString("owner") ?: ""
            modsRaw = d.get("mods")
            perms = GroupCmd.permsFrom(modsRaw, owner, me())
            banned = GroupCmd.info(d.get("banned"), "reason")
            muted = GroupCmd.untils(d.get("muted"))
            mutedReason = GroupCmd.info(d.get("muted"), "reason")
            val ws = ArrayList<String>()
            val wl = d.get("words") as? List<*>
            if (wl != null) {
                for (w in wl) ws.add(w.toString().lowercase())
            }
            words = ws
            pins = (d.get("pins") as? List<*>)?.toList() ?: emptyList()
            pinGame = d.get("pinGame") as? Map<*, *>
            input?.isEnabled = !banned.containsKey(me())
            if (banned.containsKey(me())) input?.hint = "Тебя забанили в этой группе"
            render()
        }
        reg = ref.collection("msgs")
            .orderBy("ts", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) {
                    if (err != null) toast("Нет доступа к чату группы (возможно, бан)")
                    return@addSnapshotListener
                }
                val l = ArrayList<M>()
                for (d in snap.documents) {
                    l.add(M(d.id, d.getLong("rid") ?: 0L, d.getString("uid") ?: "", d.getString("name") ?: "?", d.getString("text") ?: "", d.getString("game") ?: ""))
                }
                l.reverse()
                if (!seeded) {
                    seeded = true
                    for (m in l) hidden.add(m.id)
                }
                msgs = l
                render()
            }
    }

    private fun censor(t: String): String {
        var r = t
        for (w in words) {
            if (w.isNotEmpty()) r = r.replace(w, "*".repeat(w.length), ignoreCase = true)
        }
        return r
    }

    private fun hasBad(t: String): Boolean {
        val l = t.lowercase()
        for (w in words) {
            if (w.isNotEmpty() && l.contains(w)) return true
        }
        return false
    }

    private fun gray(): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(Color.parseColor("#9E9E9E"))
        return g
    }

    private fun render() {
        renderPins()
        val l = list ?: return
        l.removeAllViews()
        if (banned.containsKey(me())) {
            val r = banned[me()] ?: ""
            val tv = TextView(this)
            tv.text = "Тебя забанили в этой группе." + (if (r.isNotEmpty()) " Причина: " + r else "")
            tv.setTextColor(Color.RED)
            l.addView(tv)
        }
        if (perms.contains("mute") || perms.contains("ban")) {
            val tv = TextView(this)
            tv.text = GroupCmd.HELP
            tv.textSize = 12f
            tv.setTextColor(Color.GRAY)
            l.addView(tv)
        }
        val prof = getSharedPreferences("profile", MODE_PRIVATE)
        val myId = prof.getLong("id", 0L)
        for (m in msgs) {
            if (hidden.contains(m.id)) continue
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, dp(4), 0, dp(4))
            val iv = ImageView(this)
            var bmp: Bitmap? = null
            if (m.rid != 0L && m.rid == myId) {
                val f = java.io.File(filesDir, "avatar.png")
                if (f.exists()) bmp = BitmapFactory.decodeFile(f.absolutePath)
            } else {
                bmp = avatars[m.rid]
                if (bmp == null) askAvatar(m.rid)
            }
            if (bmp != null) iv.setImageBitmap(bmp) else iv.setImageDrawable(gray())
            row.addView(iv, LinearLayout.LayoutParams(dp(34), dp(34)))

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(8), 0, 0, 0)
            var ok = false
            if (m.uid.isNotEmpty()) {
                ok = if (m.rid == myId) {
                    prof.getBoolean("verified", false) && prof.getLong("verifiedId", 0L) == myId && m.uid == me()
                } else {
                    verified[m.rid] == m.uid
                }
            }
            val n = TextView(this)
            var label = m.name
            if (ok) label += " ✓"
            if (m.uid == owner) {
                label += " (владелец)"
            } else if (GroupCmd.permsFrom(modsRaw, owner, m.uid).isNotEmpty()) {
                label += " (админ)"
            }
            n.text = label
            n.textSize = 12f
            n.setTextColor(Color.parseColor(if (ok) "#1B8F4A" else "#0A6AA6"))
            col.addView(n)
            if (m.game.isNotEmpty()) {
                val g = TextView(this)
                g.text = "🎮 " + m.game
                g.textSize = 11f
                g.setTextColor(Color.GRAY)
                col.addView(g)
            }
            val t = TextView(this)
            t.text = censor(m.text)
            t.textSize = 15f
            col.addView(t)
            row.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (perms.contains("pin")) {
                row.setOnClickListener { pinMessage(m) }
            }
            l.addView(row)
        }
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun renderPins() {
        val box = pinBox ?: return
        box.removeAllViews()
        val pg = pinGame
        if (pg != null) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(4), 0, dp(4))
            val iv = ImageView(this)
            val bmp = GroupCmd.fromB64(pg["icon"] as? String)
            if (bmp != null) iv.setImageBitmap(bmp) else iv.setImageDrawable(gray())
            row.addView(iv, LinearLayout.LayoutParams(dp(30), dp(30)))
            val tv = TextView(this)
            tv.text = "Играем в: " + (pg["name"] ?: "?")
            tv.setPadding(dp(8), 0, 0, 0)
            tv.textSize = 14f
            row.addView(tv)
            if (perms.contains("game")) {
                row.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Открепить игру?")
                        .setPositiveButton("Открепить") { _, _ ->
                            fire(FirebaseFirestore.getInstance().collection("groups").document(gid).update("pinGame", FieldValue.delete()))
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
            box.addView(row)
        }
        for (p in pins) {
            val pm = p as? Map<*, *> ?: continue
            val tv = TextView(this)
            tv.text = "📌 " + (pm["name"] ?: "?") + ": " + censor((pm["text"] ?: "").toString())
            tv.textSize = 14f
            tv.setPadding(0, dp(4), 0, dp(4))
            if (perms.contains("pin")) {
                tv.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Открепить сообщение?")
                        .setPositiveButton("Открепить") { _, _ ->
                            fire(FirebaseFirestore.getInstance().collection("groups").document(gid).update("pins", FieldValue.arrayRemove(p)))
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
            box.addView(tv)
        }
    }

    private fun fire(task: com.google.android.gms.tasks.Task<Void>) {
        task.addOnFailureListener { toast("Не получилось: " + (it.message ?: it.javaClass.simpleName)) }
    }

    private fun pinMessage(m: M) {
        if (pins.size >= 3) {
            toast("Закреплено максимум 3 сообщения. Открепи одно, нажав на него вверху")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Закрепить сообщение?")
            .setMessage(m.name + ": " + m.text)
            .setPositiveButton("Закрепить") { _, _ ->
                val v = hashMapOf<String, Any>("name" to m.name, "text" to m.text)
                fire(FirebaseFirestore.getInstance().collection("groups").document(gid).update("pins", FieldValue.arrayUnion(v)))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun askAvatar(rid: Long) {
        if (rid == 0L || !asked.add(rid)) return
        FirebaseFirestore.getInstance().collection("users").document(rid.toString()).get()
            .addOnSuccessListener { d ->
                var changed = false
                val vu = d.getString("uid")
                if (d.getBoolean("verified") == true && vu != null) {
                    verified[rid] = vu
                    changed = true
                }
                val bmp = GroupCmd.fromB64(d.getString("avatar"))
                if (bmp != null) {
                    avatars[rid] = bmp
                    changed = true
                }
                if (changed) render()
            }
            .addOnFailureListener { asked.remove(rid) }
    }

    private fun send() {
        val et = input ?: return
        val t = et.text.toString().trim()
        if (t.isEmpty()) return
        val uid = me()
        if (uid.isEmpty()) {
            toast("Чат пока не подключился. Подожди или включи VPN")
            return
        }
        if (t.startsWith("/")) {
            et.setText("")
            GroupCmd.run(gid, t) { toast(it) }
            return
        }
        val prof = getSharedPreferences("profile", MODE_PRIVATE)
        val rid = prof.getLong("id", 0L)
        val name = prof.getString("displayName", "") ?: ""
        val now = System.currentTimeMillis()
        if (rid == 0L || name.isEmpty()) {
            toast("Сначала выбери ник на главном экране")
        } else if (banned.containsKey(uid)) {
            toast("Тебя забанили в этой группе")
        } else if ((muted[uid] ?: 0L) > now) {
            val r = mutedReason[uid] ?: ""
            toast("Ты в муте до " + GroupCmd.untilText(muted[uid] ?: 0L) + (if (r.isNotEmpty()) ". Причина: " + r else ""))
        } else if (hasBad(t)) {
            toast("В сообщении есть запрещённое слово")
        } else if (now - lastSend >= 1000L) {
            lastSend = now
            val text = t.take(200)
            et.setText("")
            UserSync.sync(this, rid, name, uid) {
                val data = hashMapOf<String, Any>(
                    "uid" to uid,
                    "rid" to rid,
                    "name" to name.take(40),
                    "text" to text,
                    "ts" to FieldValue.serverTimestamp()
                )
                val gname = getSharedPreferences("game", MODE_PRIVATE).getString("name", "") ?: ""
                if (gname.isNotEmpty()) data["game"] = gname.take(40)
                FirebaseFirestore.getInstance().collection("groups").document(gid).collection("msgs").add(data)
                    .addOnFailureListener { toast("Не отправилось: " + (it.message ?: it.javaClass.simpleName)) }
            }
        }
    }

    // ---------- игры ----------

    private fun showGames() {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(16), dp(8), dp(16), dp(8))
        val sv = ScrollView(this)
        sv.addView(l)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Игра")
            .setView(sv)
            .setPositiveButton("Закрыть", null)
            .create()
        val gp = getSharedPreferences("game", MODE_PRIVATE)
        val cur = gp.getString("name", "") ?: ""
        val curTv = TextView(this)
        curTv.text = if (cur.isEmpty()) "Ты сейчас не выбрал игру" else "Ты играешь в: " + cur
        l.addView(curTv)
        if (cur.isNotEmpty()) {
            val off = Button(this)
            off.text = "Я не играю"
            off.setOnClickListener {
                gp.edit().clear().apply()
                dlg.dismiss()
                toast("Игра убрана из твоих сообщений")
            }
            l.addView(off)
        }
        val et = EditText(this)
        et.hint = "Название игры"
        et.setSingleLine(true)
        l.addView(et)
        val find = Button(this)
        find.text = "Найти игру"
        l.addView(find)
        val res = LinearLayout(this)
        res.orientation = LinearLayout.VERTICAL
        l.addView(res)

        find.setOnClickListener {
            val q = et.text.toString().trim()
            if (q.isEmpty()) {
                toast("Введи название")
            } else {
                res.removeAllViews()
                val wait = TextView(this)
                wait.text = "Ищу..."
                res.addView(wait)
                Thread {
                    try {
                        val games = RobloxApi.searchGames(q)
                        val icons = HashMap<Long, Bitmap>()
                        val views = HashMap<Long, ImageView>()
                        runOnUiThread {
                            res.removeAllViews()
                            if (games.isEmpty()) {
                                val e = TextView(this)
                                e.text = "Ничего не нашли"
                                res.addView(e)
                            }
                            for (g in games) {
                                val row = LinearLayout(this)
                                row.orientation = LinearLayout.HORIZONTAL
                                row.gravity = android.view.Gravity.CENTER_VERTICAL
                                row.setPadding(0, dp(6), 0, dp(6))
                                val iv = ImageView(this)
                                iv.setImageDrawable(gray())
                                row.addView(iv, LinearLayout.LayoutParams(dp(44), dp(44)))
                                val tv = TextView(this)
                                tv.text = g.name
                                tv.setPadding(dp(10), 0, 0, 0)
                                row.addView(tv)
                                row.setOnClickListener { chooseGame(g, icons[g.id], dlg) }
                                res.addView(row)
                                views[g.id] = iv
                            }
                        }
                        try {
                            val urls = RobloxApi.gameIcons(games.map { it.id })
                            for (g in games) {
                                val u = urls[g.id] ?: continue
                                val bmp = try {
                                    RobloxApi.loadBitmap(u)
                                } catch (e: Exception) {
                                    null
                                }
                                if (bmp != null) {
                                    icons[g.id] = bmp
                                    runOnUiThread { views[g.id]?.setImageBitmap(bmp) }
                                }
                            }
                        } catch (e: Exception) {
                        }
                    } catch (e: Throwable) {
                        runOnUiThread {
                            res.removeAllViews()
                            val er = TextView(this)
                            er.text = "Не получилось найти: " + (e.message ?: e.javaClass.simpleName) + "\nЕсли Роблокс у тебя работает только с VPN, включи его."
                            res.addView(er)
                        }
                    }
                }.start()
            }
        }
        dlg.show()
    }

    private fun chooseGame(g: RbGame, icon: Bitmap?, parent: AlertDialog) {
        val items = ArrayList<String>()
        items.add("Я играю в эту игру")
        if (perms.contains("game")) items.add("Закрепить для всей группы")
        AlertDialog.Builder(this)
            .setTitle(g.name)
            .setItems(items.toTypedArray()) { _, i ->
                if (i == 0) {
                    getSharedPreferences("game", MODE_PRIVATE).edit()
                        .putLong("id", g.id).putString("name", g.name).apply()
                    toast("Игра будет показана в твоих сообщениях")
                    parent.dismiss()
                } else {
                    val v = hashMapOf<String, Any>(
                        "id" to g.id,
                        "name" to g.name,
                        "icon" to (if (icon != null) GroupCmd.b64(icon) else "")
                    )
                    fire(FirebaseFirestore.getInstance().collection("groups").document(gid).update("pinGame", v))
                    parent.dismiss()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- участники и права ----------

    private fun showMembers() {
        val ref = FirebaseFirestore.getInstance().collection("groups").document(gid)
        ref.collection("members").get()
            .addOnSuccessListener { s ->
                val l = LinearLayout(this)
                l.orientation = LinearLayout.VERTICAL
                l.setPadding(dp(16), dp(8), dp(16), dp(8))
                val sv = ScrollView(this)
                sv.addView(l)
                val dlg = AlertDialog.Builder(this)
                    .setTitle("Участники (" + s.size() + ")")
                    .setView(sv)
                    .setPositiveButton("Закрыть", null)
                    .create()
                val iAmOwner = me() == owner
                for (d in s.documents) {
                    val uid = d.id
                    val name = d.getString("name") ?: "?"
                    val un = d.getString("uname") ?: ""
                    val p = GroupCmd.permsFrom(modsRaw, owner, uid)
                    var role = "участник"
                    if (uid == owner) role = "владелец" else if (p.isNotEmpty()) role = "админ"
                    var extra = ""
                    if (banned.containsKey(uid)) extra += " · бан"
                    if ((muted[uid] ?: 0L) > System.currentTimeMillis()) extra += " · мут"
                    val b = Button(this)
                    b.isAllCaps = false
                    b.text = name + " (@" + un + ")\n" + role + extra
                    b.setOnClickListener {
                        if (iAmOwner && uid != owner) {
                            dlg.dismiss()
                            editRights(uid, name, p)
                        } else if ((perms.contains("mute") || perms.contains("ban")) && uid != owner && uid != me()) {
                            input?.setText("/mute @" + un + " 1 ")
                            input?.setSelection(input?.text?.length ?: 0)
                            dlg.dismiss()
                            toast("Допиши причину или замени команду на /ban @" + un)
                        }
                    }
                    l.addView(b)
                }
                if (perms.isNotEmpty()) {
                    val hint = TextView(this)
                    hint.text = (if (iAmOwner) "Нажми на участника, чтобы выдать или забрать права админа.\n" else "Нажми на участника, чтобы подставить команду.\n") + GroupCmd.HELP
                    hint.textSize = 12f
                    hint.setPadding(0, dp(10), 0, 0)
                    l.addView(hint)
                    val st = Button(this)
                    st.text = "Настройки группы"
                    st.setOnClickListener {
                        dlg.dismiss()
                        GroupsUi.manage(this)
                    }
                    l.addView(st)
                }
                dlg.show()
            }
            .addOnFailureListener { toast("Не получилось: " + (it.message ?: it.javaClass.simpleName)) }
    }

    private fun editRights(uid: String, name: String, cur: Set<String>) {
        val keys = arrayOf("mute", "ban", "pin", "words", "game")
        val names = arrayOf("Мут", "Бан", "Закреплять сообщения", "Запрещённые слова", "Искать и закреплять игры")
        val checked = BooleanArray(keys.size) { cur.contains(keys[it]) }
        AlertDialog.Builder(this)
            .setTitle("Права: " + name)
            .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Сохранить") { _, _ ->
                val sel = ArrayList<String>()
                for (i in keys.indices) if (checked[i]) sel.add(keys[i])
                val ref = FirebaseFirestore.getInstance().collection("groups").document(gid)
                if (sel.isEmpty()) {
                    fire(ref.update("mods." + uid, FieldValue.delete()))
                    toast("Права сняты")
                } else {
                    val v = hashMapOf<String, Any>("name" to name, "perms" to sel)
                    fire(ref.update("mods." + uid, v))
                    toast("Права сохранены")
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onDestroy() {
        reg?.remove()
        grpReg?.remove()
        super.onDestroy()
    }
}
