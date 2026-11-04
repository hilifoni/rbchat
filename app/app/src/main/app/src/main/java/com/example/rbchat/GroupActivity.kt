package com.example.rbchat

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
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

    private class M(val id: String, val rid: Long, val uid: String, val name: String, val text: String)

    private var gid = ""
    private var titleTv: TextView? = null
    private var list: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var input: EditText? = null
    private var msgs: List<M> = emptyList()
    private var reg: ListenerRegistration? = null
    private var grpReg: ListenerRegistration? = null
    private var owner = ""
    private var mods: Set<String> = emptySet()
    private var banned: Map<String, String> = emptyMap()
    private var muted: Map<String, Long> = emptyMap()
    private var mutedReason: Map<String, String> = emptyMap()
    private var words: List<String> = emptyList()
    private val avatars = HashMap<Long, Bitmap>()
    private val asked = HashSet<Long>()
    private val verified = HashMap<Long, String>()
    private var lastSend = 0L
    private var started = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    private fun me(): String = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        gid = getSharedPreferences("room", MODE_PRIVATE).getString("gid", "") ?: ""
        if (gid.isEmpty()) {
            toast("Сначала выбери группу")
            finish()
            return
        }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        val title = TextView(this)
        title.text = (getSharedPreferences("room", MODE_PRIVATE).getString("gname", "") ?: "") + "  ▾ участники"
        title.textSize = 18f
        title.setPadding(dp(14), dp(12), dp(14), dp(12))
        title.setBackgroundColor(Color.parseColor("#E0E0E0"))
        title.setOnClickListener { showMembers() }
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        titleTv = title

        val sv = ScrollView(this)
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(10), dp(8), dp(10), dp(8))
        sv.addView(l, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
        row.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val b = Button(this)
        b.text = "Отпр."
        b.setOnClickListener { send() }
        row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
            val nm = d.getString("name") ?: "Группа"
            titleTv?.text = nm + "  ▾ участники"
            owner = d.getString("owner") ?: ""
            mods = (d.get("mods") as? Map<*, *>)?.keys?.map { it.toString() }?.toSet() ?: emptySet()
            banned = GroupCmd.info(d.get("banned"), "reason")
            muted = GroupCmd.untils(d.get("muted"))
            mutedReason = GroupCmd.info(d.get("muted"), "reason")
            val ws = ArrayList<String>()
            val wl = d.get("words") as? List<*>
            if (wl != null) {
                for (w in wl) ws.add(w.toString().lowercase())
            }
            words = ws
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
                    l.add(M(d.id, d.getLong("rid") ?: 0L, d.getString("uid") ?: "", d.getString("name") ?: "?", d.getString("text") ?: ""))
                }
                l.reverse()
                msgs = l
                render()
            }
    }

    private fun canModerate(): Boolean = me().isNotEmpty() && (me() == owner || mods.contains(me()))

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
        val l = list ?: return
        l.removeAllViews()
        if (banned.containsKey(me())) {
            val r = banned[me()] ?: ""
            val tv = TextView(this)
            tv.text = "Тебя забанили в этой группе." + (if (r.isNotEmpty()) " Причина: " + r else "")
            tv.setTextColor(Color.RED)
            l.addView(tv)
        }
        if (canModerate()) {
            val tv = TextView(this)
            tv.text = GroupCmd.HELP
            tv.textSize = 12f
            tv.setTextColor(Color.GRAY)
            l.addView(tv)
        }
        val prof = getSharedPreferences("profile", MODE_PRIVATE)
        val myId = prof.getLong("id", 0L)
        for (m in msgs) {
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
            if (m.uid == owner) label += " (владелец)" else if (mods.contains(m.uid)) label += " (админ)"
            n.text = label
            n.textSize = 12f
            n.setTextColor(Color.parseColor(if (ok) "#1B8F4A" else "#0A6AA6"))
            val t = TextView(this)
            t.text = censor(m.text)
            t.textSize = 15f
            col.addView(n)
            col.addView(t)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            l.addView(row)
        }
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
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
                val s = d.getString("avatar")
                if (s != null && s.isNotEmpty()) {
                    try {
                        val bytes = Base64.decode(s, Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            avatars[rid] = bmp
                            changed = true
                        }
                    } catch (e: Throwable) {
                    }
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
                FirebaseFirestore.getInstance().collection("groups").document(gid).collection("msgs").add(data)
                    .addOnFailureListener { toast("Не отправилось: " + (it.message ?: it.javaClass.simpleName)) }
            }
        }
    }

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
                    var role = "участник"
                    if (uid == owner) role = "владелец" else if (mods.contains(uid)) role = "админ"
                    var extra = ""
                    if (banned.containsKey(uid)) extra += " · бан"
                    if ((muted[uid] ?: 0L) > System.currentTimeMillis()) extra += " · мут"
                    val b = Button(this)
                    b.isAllCaps = false
                    b.text = name + " (@" + un + ")\n" + role + extra
                    b.setOnClickListener {
                        if (iAmOwner && uid != owner) {
                            val isMod = mods.contains(uid)
                            AlertDialog.Builder(this)
                                .setTitle(name)
                                .setItems(arrayOf(if (isMod) "Снять админку" else "Дать админку")) { _, _ ->
                                    val task = if (isMod) ref.update("mods." + uid, FieldValue.delete()) else ref.update("mods." + uid, name)
                                    task.addOnSuccessListener {
                                        toast(if (isMod) "Админка снята" else "Теперь админ")
                                        dlg.dismiss()
                                    }.addOnFailureListener { toast("Не получилось: " + (it.message ?: it.javaClass.simpleName)) }
                                }
                                .setNegativeButton("Отмена", null)
                                .show()
                        } else if (canModerate() && uid != owner && uid != me()) {
                            input?.setText("/mute @" + un + " 1 ")
                            input?.setSelection(input?.text?.length ?: 0)
                            dlg.dismiss()
                            toast("Допиши причину или замени команду на /ban @" + un)
                        }
                    }
                    l.addView(b)
                }
                if (canModerate()) {
                    val hint = TextView(this)
                    hint.text = GroupCmd.HELP + ". Нажми на участника, чтобы подставить команду" + (if (iAmOwner) " (владелец: нажми, чтобы дать или снять админку)" else "")
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

    override fun onDestroy() {
        reg?.remove()
        grpReg?.remove()
        super.onDestroy()
    }
}
