package com.example.rbchat

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

object GroupsUi {

    private const val ABC = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private fun toast(a: Activity, s: String) {
        Toast.makeText(a, s, Toast.LENGTH_LONG).show()
    }

    private fun uid(): String = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    private fun dp(a: Activity, v: Int): Int = (v * a.resources.displayMetrics.density).toInt()

    private fun setRoom(a: Activity, gid: String, name: String) {
        a.getSharedPreferences("room", Context.MODE_PRIVATE).edit()
            .putString("gid", gid).putString("gname", name).apply()
    }

    private fun curGid(a: Activity): String =
        a.getSharedPreferences("room", Context.MODE_PRIVATE).getString("gid", "") ?: ""

    fun section(a: Activity): LinearLayout {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL

        val title = TextView(a)
        title.text = "Группы"
        title.textSize = 16f
        title.setPadding(0, dp(a, 12), 0, 0)
        box.addView(title)

        val cur = TextView(a)
        cur.textSize = 14f
        box.addView(cur)

        fun refresh() {
            val p = a.getSharedPreferences("room", Context.MODE_PRIVATE)
            val g = p.getString("gid", "") ?: ""
            cur.text = if (g.isEmpty()) {
                "Сейчас: общий чат"
            } else {
                "Сейчас: " + (p.getString("gname", "") ?: "") + " (код " + g + ")"
            }
        }
        refresh()

        val general = Button(a)
        general.text = "Общий чат"
        general.setOnClickListener {
            setRoom(a, "", "")
            refresh()
            toast(a, "Включён общий чат")
        }
        box.addView(general)

        val create = Button(a)
        create.text = "Создать группу"
        create.setOnClickListener { createDialog(a) { refresh() } }
        box.addView(create)

        val codeEt = EditText(a)
        codeEt.hint = "Код группы"
        codeEt.setSingleLine(true)
        box.addView(codeEt)

        val join = Button(a)
        join.text = "Войти по коду"
        join.setOnClickListener { joinByCode(a, codeEt.text.toString()) { refresh() } }
        box.addView(join)

        val pub = Button(a)
        pub.text = "Публичные группы"
        pub.setOnClickListener { publicList(a) { refresh() } }
        box.addView(pub)

        val manage = Button(a)
        manage.text = "Управление группой"
        manage.setOnClickListener { manage(a) }
        box.addView(manage)

        return box
    }

    private fun createDialog(a: Activity, done: () -> Unit) {
        val me = uid()
        val prof = a.getSharedPreferences("profile", Context.MODE_PRIVATE)
        if (me.isEmpty()) {
            toast(a, "Нет входа в Firebase. Включи VPN и открой приложение заново")
            return
        }
        if (prof.getLong("id", 0L) == 0L) {
            toast(a, "Сначала выбери свой ник")
            return
        }
        val l = LinearLayout(a)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0)
        val name = EditText(a)
        name.hint = "Название группы"
        name.setSingleLine(true)
        l.addView(name)
        val pub = CheckBox(a)
        pub.text = "Публичная (видна всем в списке)"
        l.addView(pub)
        AlertDialog.Builder(a)
            .setTitle("Новая группа")
            .setView(l)
            .setPositiveButton("Создать") { _, _ ->
                val n = name.text.toString().trim().take(30)
                if (n.isEmpty()) {
                    toast(a, "Введи название")
                } else {
                    val sb = StringBuilder()
                    for (i in 0 until 6) sb.append(ABC[java.util.Random().nextInt(ABC.length)])
                    val gid = sb.toString()
                    val data = hashMapOf<String, Any>(
                        "name" to n,
                        "public" to pub.isChecked,
                        "owner" to me,
                        "ownerName" to (prof.getString("displayName", "") ?: ""),
                        "mods" to HashMap<String, Any>(),
                        "banned" to HashMap<String, Any>(),
                        "muted" to HashMap<String, Any>(),
                        "words" to ArrayList<String>()
                    )
                    FirebaseFirestore.getInstance().collection("groups").document(gid).set(data)
                        .addOnSuccessListener {
                            setRoom(a, gid, n)
                            done()
                            toast(a, "Группа создана. Код: " + gid)
                        }
                        .addOnFailureListener {
                            toast(a, "Не получилось: " + (it.message ?: it.javaClass.simpleName))
                        }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun joinByCode(a: Activity, code: String, done: () -> Unit) {
        val c = code.trim().uppercase()
        if (c.isEmpty()) {
            toast(a, "Введи код")
            return
        }
        if (uid().isEmpty()) {
            toast(a, "Нет входа в Firebase. Включи VPN и открой приложение заново")
            return
        }
        FirebaseFirestore.getInstance().collection("groups").document(c).get()
            .addOnSuccessListener { d ->
                val banned = d.get("banned") as? Map<*, *>
                if (!d.exists()) {
                    toast(a, "Группа с таким кодом не найдена")
                } else if (banned != null && banned.containsKey(uid())) {
                    toast(a, "Тебя забанили в этой группе")
                } else {
                    setRoom(a, c, d.getString("name") ?: c)
                    done()
                    toast(a, "Ты в группе")
                }
            }
            .addOnFailureListener {
                toast(a, "Не получилось: " + (it.message ?: it.javaClass.simpleName))
            }
    }

    private fun publicList(a: Activity, done: () -> Unit) {
        if (uid().isEmpty()) {
            toast(a, "Нет входа в Firebase. Включи VPN и открой приложение заново")
            return
        }
        FirebaseFirestore.getInstance().collection("groups").whereEqualTo("public", true).limit(30).get()
            .addOnSuccessListener { s ->
                if (s.isEmpty) {
                    toast(a, "Публичных групп пока нет")
                } else {
                    val ids = s.documents.map { it.id }
                    val names = s.documents.map { (it.getString("name") ?: "?") }.toTypedArray()
                    AlertDialog.Builder(a)
                        .setTitle("Публичные группы")
                        .setItems(names) { _, i ->
                            joinByCode(a, ids[i], done)
                        }
                        .setNegativeButton("Закрыть", null)
                        .show()
                }
            }
            .addOnFailureListener {
                toast(a, "Не получилось: " + (it.message ?: it.javaClass.simpleName))
            }
    }

    private fun manage(a: Activity) {
        val gid = curGid(a)
        if (gid.isEmpty()) {
            toast(a, "В общем чате управлять нечем. Выбери группу")
            return
        }
        FirebaseFirestore.getInstance().collection("groups").document(gid).get()
            .addOnSuccessListener { d ->
                val me = uid()
                val owner = d.getString("owner") ?: ""
                val mods = d.get("mods") as? Map<*, *> ?: emptyMap<String, Any>()
                if (!d.exists()) {
                    toast(a, "Группа не найдена")
                } else if (me != owner && !mods.containsKey(me)) {
                    toast(a, "У тебя нет прав в этой группе")
                } else {
                    showManage(a, gid, d, me == owner)
                }
            }
            .addOnFailureListener {
                toast(a, "Не получилось: " + (it.message ?: it.javaClass.simpleName))
            }
    }

    private fun showManage(
        a: Activity,
        gid: String,
        d: com.google.firebase.firestore.DocumentSnapshot,
        isOwner: Boolean
    ) {
        val ref = FirebaseFirestore.getInstance().collection("groups").document(gid)
        val l = LinearLayout(a)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 8))
        val sv = ScrollView(a)
        sv.addView(l)
        val dlg = AlertDialog.Builder(a)
            .setTitle((d.getString("name") ?: "Группа"))
            .setView(sv)
            .setPositiveButton("Закрыть", null)
            .create()

        fun again(task: com.google.android.gms.tasks.Task<Void>) {
            task.addOnSuccessListener {
                dlg.dismiss()
                manage(a)
            }.addOnFailureListener {
                toast(a, "Не получилось: " + (it.message ?: it.javaClass.simpleName))
            }
        }

        fun label(t: String) {
            val tv = TextView(a)
            tv.text = t
            tv.textSize = 15f
            tv.setPadding(0, dp(a, 12), 0, dp(a, 4))
            l.addView(tv)
        }

        val code = TextView(a)
        code.text = "Код группы: " + gid + (if (d.getBoolean("public") == true) " (публичная)" else " (закрытая)")
        code.setTextIsSelectable(true)
        l.addView(code)

        label("Чёрный список слов (нажми на слово, чтобы убрать)")
        val words = d.get("words") as? List<*> ?: emptyList<Any>()
        if (words.isEmpty()) {
            val e = TextView(a)
            e.text = "Пока пусто"
            l.addView(e)
        }
        for (w in words) {
            val b = Button(a)
            b.text = "✕ " + w.toString()
            b.setOnClickListener { again(ref.update("words", FieldValue.arrayRemove(w.toString()))) }
            l.addView(b)
        }
        val wEt = EditText(a)
        wEt.hint = "Новое слово"
        wEt.setSingleLine(true)
        l.addView(wEt)
        val add = Button(a)
        add.text = "Добавить слово"
        add.setOnClickListener {
            val w = wEt.text.toString().trim().lowercase().take(30)
            if (w.isEmpty()) {
                toast(a, "Введи слово")
            } else {
                again(ref.update("words", FieldValue.arrayUnion(w)))
            }
        }
        l.addView(add)

        label("Забанены (нажми, чтобы разбанить)")
        val banned = d.get("banned") as? Map<*, *> ?: emptyMap<String, Any>()
        if (banned.isEmpty()) {
            val e = TextView(a)
            e.text = "Никого"
            l.addView(e)
        }
        for ((k, v) in banned) {
            val b = Button(a)
            b.text = "Разбанить: " + v.toString()
            b.setOnClickListener { again(ref.update("banned." + k.toString(), FieldValue.delete())) }
            l.addView(b)
        }

        label("В муте (нажми, чтобы снять)")
        val muted = d.get("muted") as? Map<*, *> ?: emptyMap<String, Any>()
        if (muted.isEmpty()) {
            val e = TextView(a)
            e.text = "Никого"
            l.addView(e)
        }
        for ((k, v) in muted) {
            val nm = ((v as? Map<*, *>)?.get("name") ?: "?").toString()
            val b = Button(a)
            b.text = "Снять мут: " + nm
            b.setOnClickListener { again(ref.update("muted." + k.toString(), FieldValue.delete())) }
            l.addView(b)
        }

        if (isOwner) {
            label("Модераторы (нажми, чтобы убрать)")
            val mods = d.get("mods") as? Map<*, *> ?: emptyMap<String, Any>()
            if (mods.isEmpty()) {
                val e = TextView(a)
                e.text = "Никого. Модератора можно назначить в чате: нажми на сообщение игрока и «В модеры»"
                l.addView(e)
            }
            for ((k, v) in mods) {
                val b = Button(a)
                b.text = "Убрать: " + v.toString()
                b.setOnClickListener { again(ref.update("mods." + k.toString(), FieldValue.delete())) }
                l.addView(b)
            }
            val del = Button(a)
            del.text = "Удалить группу"
            del.setOnClickListener {
                AlertDialog.Builder(a)
                    .setTitle("Удалить группу?")
                    .setMessage("Группа пропадёт для всех.")
                    .setPositiveButton("Удалить") { _, _ ->
                        ref.delete().addOnSuccessListener {
                            setRoom(a, "", "")
                            dlg.dismiss()
                            toast(a, "Группа удалена. Включён общий чат")
                        }.addOnFailureListener {
                            toast(a, "Не получилось: " + (it.message ?: it.javaClass.simpleName))
                        }
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            l.addView(del)
        }
        dlg.show()
    }
}
