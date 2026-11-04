package com.example.rbchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

object GroupCmd {

    // значения banned/muted: либо строка (старый формат), либо словарь name/reason/until
    fun info(raw: Any?, key: String): Map<String, String> {
        val r = HashMap<String, String>()
        val m = raw as? Map<*, *> ?: return r
        for ((k, v) in m) {
            val x = v as? Map<*, *>
            r[k.toString()] = if (x != null) (x[key] ?: "").toString() else if (key == "name") v.toString() else ""
        }
        return r
    }

    fun untils(raw: Any?): Map<String, Long> {
        val r = HashMap<String, Long>()
        val m = raw as? Map<*, *> ?: return r
        for ((k, v) in m) {
            r[k.toString()] = ((v as? Map<*, *>)?.get("until") as? Number)?.toLong() ?: 0L
        }
        return r
    }

    fun untilText(t: Long): String =
        java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))

    const val HELP = "Команды: /mute @ник дни причина, /unmute @ник, /ban @ник причина, /unban @ник"

    fun run(gid: String, text: String, toast: (String) -> Unit) {
        val me = FirebaseAuth.getInstance().currentUser?.uid
        if (me == null) {
            toast("Нет входа в Firebase")
            return
        }
        val t = text.trim().split(Regex("\\s+"))
        val cmd = t[0].lowercase()
        if (cmd != "/mute" && cmd != "/unmute" && cmd != "/ban" && cmd != "/unban") {
            toast(HELP)
            return
        }
        if (t.size < 2) {
            toast(HELP)
            return
        }
        val target = t[1].removePrefix("@")
        var days = 0.0
        var reasonFrom = 2
        if (cmd == "/mute") {
            val d = t.getOrNull(2)?.replace(',', '.')?.toDoubleOrNull()
            if (d == null || d <= 0.0) {
                toast("Укажи дни: /mute @ник 3 причина")
                return
            }
            days = d
            reasonFrom = 3
        }
        val reason = if (cmd == "/mute" || cmd == "/ban") t.drop(reasonFrom).joinToString(" ").take(100) else ""
        val ref = FirebaseFirestore.getInstance().collection("groups").document(gid)
        ref.get().addOnSuccessListener { g ->
            val owner = g.getString("owner") ?: ""
            val mods = (g.get("mods") as? Map<*, *>)?.keys?.map { it.toString() }?.toSet() ?: emptySet()
            if (me != owner && !mods.contains(me)) {
                toast("У тебя нет прав в этой группе")
                return@addOnSuccessListener
            }
            ref.collection("members").get().addOnSuccessListener { ms ->
                var uid = ""
                var name = ""
                for (d in ms.documents) {
                    val n = d.getString("name") ?: ""
                    val u = d.getString("uname") ?: ""
                    if (u.equals(target, true) || n.equals(target, true)) {
                        uid = d.id
                        name = n
                        break
                    }
                }
                if (uid.isEmpty()) {
                    toast("Не нашёл участника " + target + ". Пиши @ник (имя пользователя Роблокса)")
                } else if (uid == owner) {
                    toast("Владельца трогать нельзя")
                } else if (uid == me) {
                    toast("Себя нельзя")
                } else if (mods.contains(uid) && me != owner) {
                    toast("Админа может наказать только владелец")
                } else {
                    val fail = { e: Exception -> toast("Не получилось: " + (e.message ?: e.javaClass.simpleName)) }
                    if (cmd == "/mute") {
                        val until = System.currentTimeMillis() + (days * 86400000.0).toLong()
                        val v = hashMapOf<String, Any>("until" to until, "name" to name, "reason" to reason)
                        ref.update("muted." + uid, v)
                            .addOnSuccessListener { toast(name + " в муте до " + untilText(until)) }
                            .addOnFailureListener { fail(it) }
                    } else if (cmd == "/unmute") {
                        ref.update("muted." + uid, FieldValue.delete())
                            .addOnSuccessListener { toast("Мут снят с " + name) }
                            .addOnFailureListener { fail(it) }
                    } else if (cmd == "/ban") {
                        val v = hashMapOf<String, Any>("name" to name, "reason" to reason)
                        ref.update("banned." + uid, v)
                            .addOnSuccessListener { toast(name + " забанен") }
                            .addOnFailureListener { fail(it) }
                    } else {
                        ref.update("banned." + uid, FieldValue.delete())
                            .addOnSuccessListener { toast(name + " разбанен") }
                            .addOnFailureListener { fail(it) }
                    }
                }
            }.addOnFailureListener { toast("Не получилось: " + (it.message ?: it.javaClass.simpleName)) }
        }.addOnFailureListener { toast("Не получилось: " + (it.message ?: it.javaClass.simpleName)) }
    }
}

object UserSync {
    // записывает в базу аватарку, подтверждение и uid (если что-то изменилось), потом вызывает next
    fun sync(ctx: Context, rid: Long, name: String, uid: String, next: () -> Unit) {
        val f = java.io.File(ctx.filesDir, "avatar.png")
        val prefs = ctx.getSharedPreferences("profile", Context.MODE_PRIVATE)
        val stamp = f.lastModified()
        val verNow = prefs.getBoolean("verified", false) && prefs.getLong("verifiedId", 0L) == rid
        val avatarChanged = f.exists() && prefs.getLong("avatarSent", 0L) != stamp
        val verChanged = prefs.getBoolean("verSynced", false) != verNow
        val uidChanged = prefs.getString("uidSynced", "") != uid
        if (!avatarChanged && !verChanged && !uidChanged) {
            next()
            return
        }
        try {
            var b64 = ""
            if (f.exists()) {
                val src = BitmapFactory.decodeFile(f.absolutePath)
                if (src != null) {
                    val small = Bitmap.createScaledBitmap(src, 64, 64, true)
                    val out = java.io.ByteArrayOutputStream()
                    small.compress(Bitmap.CompressFormat.PNG, 100, out)
                    b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                }
            }
            val data = hashMapOf<String, Any>(
                "name" to name,
                "avatar" to b64,
                "uid" to uid,
                "verified" to verNow
            )
            FirebaseFirestore.getInstance().collection("users").document(rid.toString()).set(data)
                .addOnCompleteListener { t ->
                    if (t.isSuccessful) {
                        prefs.edit()
                            .putLong("avatarSent", stamp)
                            .putBoolean("verSynced", verNow)
                            .putString("uidSynced", uid)
                            .apply()
                    }
                    next()
                }
        } catch (e: Throwable) {
            next()
        }
    }
}
