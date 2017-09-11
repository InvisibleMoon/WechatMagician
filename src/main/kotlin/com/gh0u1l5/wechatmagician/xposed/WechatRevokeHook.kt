package com.gh0u1l5.wechatmagician.xposed

import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.XModuleResources
import android.os.Bundle
import android.os.Handler
import android.preference.Preference
import android.view.View
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import com.gh0u1l5.wechatmagician.R
import com.gh0u1l5.wechatmagician.util.MessageUtil
import com.google.gson.Gson
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.System.currentTimeMillis

class WechatMessage(val msgId: Int, val type: Int, val talker: String, var content: String) {
    val time: Long = currentTimeMillis()
    init { if (type != 1) content = "" }
}

class WechatRevokeHook(private val ver: WechatVersion, private val res: XModuleResources) {

    private var msgTable: List<WechatMessage> = listOf()

    fun hook(loader: ClassLoader?) {
        try {
            hookDatabase(loader)
            hookRevoke(loader)
            hookSns(loader)
        } catch(e: NoSuchMethodError) {
            when {
                e.message!!.contains(ver.SQLiteDatabaseClass) -> {
                    XposedBridge.log("NSME => ${ver.SQLiteDatabaseClass}")
                    XpWechat._ver?.SQLiteDatabaseClass = ""
                }
                e.message!!.contains("${ver.recallClass}#${ver.recallMethod}") -> {
                    XposedBridge.log("NSME => ${ver.recallClass}#${ver.recallMethod}")
                    XpWechat._ver?.recallClass = ""
                    XpWechat._ver?.recallMethod = ""
                }
                else -> throw e
            }
        } catch(t: Throwable) {
            XposedBridge.log(t)
        }
    }

    @Synchronized
    private fun addMessage(msgId: Int, type: Int, talker: String, content: String) {
        msgTable += WechatMessage(msgId, type, talker,content)
    }

    @Synchronized
    private fun getMessage(msgId: Int): WechatMessage? {
        return msgTable.find { it.msgId == msgId }
    }

    @Synchronized
    private fun cleanMessage() {
        msgTable = msgTable.filter { currentTimeMillis() - it.time < 120000 }
    }

    @Suppress("UNCHECKED_CAST")
    private fun hookRevoke(loader: ClassLoader?) {
        if (ver.recallClass == "" || ver.recallMethod == "") return

        XposedHelpers.findAndHookMethod(ver.recallClass, loader, ver.recallMethod, String::class.java, String::class.java, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = (param.result as? MutableMap<String, String?>)?.apply {
                    if (this[".sysmsg.\$type"] != "revokemsg") return
                    this[".sysmsg.revokemsg.replacemsg"] = this[".sysmsg.revokemsg.replacemsg"]?.let {
                        if (it.startsWith("你") || it.toLowerCase().startsWith("you")) it
                        else MessageUtil.customize(it, res.getString(R.string.easter_egg))
                    }
                }
            }
        })
    }

    private fun hookDatabase(loader: ClassLoader?) {
        if (ver.SQLiteDatabaseClass == "") return

        XposedHelpers.findAndHookMethod(ver.SQLiteDatabaseClass, loader, "insertWithOnConflict", String::class.java, String::class.java, ContentValues::class.java, Integer.TYPE, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                val p1 = param.args[0] as String?
                val p2 = param.args[1] as String?
                val p3 = param.args[2] as ContentValues?
                val p4 = param.args[3] as Int

                if (p1 == "message") {
                    p3?.apply {
                        if (!containsKey("type") || !containsKey("talker")) {
                            XposedBridge.log("DB => insert p1 = $p1, p2 = $p2, p3 = $p3, p4 = $p4")
                            return
                        }
                        addMessage(getAsInteger("msgId"), getAsInteger("type"), getAsString("talker"), getAsString("content"))
                    }
                    cleanMessage()
                }
            }
        })

        XposedHelpers.findAndHookMethod(ver.SQLiteDatabaseClass, loader, "updateWithOnConflict", String::class.java, ContentValues::class.java, String::class.java, Array<String?>::class.java, Integer.TYPE, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                val p1 = param.args[0] as String?
                val p2 = param.args[1] as ContentValues?
//                val p3 = param.args[2] as String?
//                val p4 = param.args[3] as Array<*>?
//                val p5 = param.args[4] as Int
//                XposedBridge.log("DB => update p1 = $p1, p2 = $p2, p3 = $p3, p4 = ${MessageUtil.argsToString(p4)}, p5 = $p5")

                val label_recalled = res.getString(R.string.label_recalled)
                val label_deleted = res.getString(R.string.label_deleted)

                if (p1 == "message") {
                    p2?.apply {
                        if (getAsInteger("type") != 10000){
                            return
                        }
                        val sysMsg = getAsString("content")
                        if (sysMsg.startsWith("你") || sysMsg.toLowerCase().startsWith("you")) {
                            return
                        }
                        remove("content"); remove("type")
                        getMessage(getAsInteger("msgId"))?.let {
                            if (it.type != 1) return
                            if (it.talker.contains("chatroom"))
                                put("content", MessageUtil.notifyChatroomRecall(label_recalled, it.content))
                            else
                                put("content", MessageUtil.notifyPrivateRecall(label_recalled, it.content))
                        }
                    }
                }
                if (p1 == "SnsInfo") {
                    p2?.apply {
                        if (!containsKey("sourceType") || this["sourceType"] != 0){
                            return
                        }
                        put("content", MessageUtil.notifyInfoDelete(label_deleted, getAsByteArray("content")))
                        remove("sourceType")
                    }
                }
                if (p1 == "SnsComment") {
                    p2?.apply {
                        if (!containsKey("type") || !containsKey("commentflag")){
                            return
                        }
                        if (this["type"] == 1 || this["commentflag"] != 1){
                            return
                        }
                        put("curActionBuf", MessageUtil.notifyCommentDelete(label_deleted, getAsByteArray("curActionBuf")))
                        remove("commentflag")
                    }
                }
            }
        })

//        XposedHelpers.findAndHookMethod(ver.SQLiteDatabaseClass, loader, "delete", String::class.java, String::class.java, Array<String?>::class.java, object : XC_MethodHook() {
//            @Throws(Throwable::class)
//            override fun beforeHookedMethod(param: MethodHookParam) {
//                val p1 = param.args[0] as String?
//                val p2 = param.args[1] as String?
//                val p3 = param.args[2] as Array<*?>?
//                XposedBridge.log("DB => delete p1 = $p1, p2 = $p2, p3 = ${MessageUtil.argsToString(p3)}")
//            }
//        })

//        XposedHelpers.findAndHookMethod(ver.SQLiteDatabaseClass, loader, "executeSql", String::class.java, Array<Any?>::class.java, object : XC_MethodHook() {
//            @Throws(Throwable::class)
//            override fun beforeHookedMethod(param: MethodHookParam) {
//                val p1 = param.args[0] as String?
//                val p2 = param.args[1] as Array<*?>?
//                XposedBridge.log("DB => executeSqlxecSQL p1 = $p1, p2 = ${MessageUtil.argsToString(p2)}")
//            }
//        })
    }
    
    private fun hookSns(loader: ClassLoader?) {
        XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun afterHookedMethod(param: MethodHookParam) {

                var snsMessageRecorder = mutableSetOf<String>()

                var taskFlag = true
                var currentSnsMemberIndex = 0

                XposedHelpers.findAndHookMethod("com.tencent.mm.plugin.chatroom.ui.ChatroomInfoUI", loader, "onCreate", Bundle::class.java, object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val obj = param.thisObject
                    }
                })
                XposedHelpers.findAndHookMethod("com.tencent.mm.plugin.chatroom.ui.SeeRoomMemberUI", loader, "onCreate", Bundle::class.java, object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val obj = param.thisObject
                        val rootView = XposedHelpers.getObjectField(obj, "kAq") as GridView
                        val adapter = XposedHelpers.getObjectField(obj, "kAx") as BaseAdapter

                        Thread(Runnable() {
                            while (true) {
                                if (currentSnsMemberIndex >= adapter.count) {
                                    //XposedBridge.log("DBG -> trigger exit case")
                                    return@Runnable
                                }
                                else if (taskFlag) {
                                    taskFlag = false
                                    //XposedBridge.log("DBG -> current sns member index is ${currentSnsMemberIndex}")
                                    rootView.post(Runnable {
                                        rootView.performItemClick(adapter.getView(currentSnsMemberIndex, null, null),
                                                currentSnsMemberIndex,
                                                adapter.getItemId(currentSnsMemberIndex))
                                        currentSnsMemberIndex += 1
                                    })
                                }
                                else {
                                    Thread.sleep(500)
                                    //XposedBridge.log("DBG -> thread sleep")
                                }
                            }
                        }).start()
                    }
                })
                XposedHelpers.findAndHookMethod("com.tencent.mm.plugin.profile.ui.ContactInfoUI", loader, "onCreate", Bundle::class.java, object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val obj = param.thisObject
                        val snsInstance = XposedHelpers.getObjectField(obj, "ooW")

                        XposedHelpers.callMethod(snsInstance, "rp", "contact_info_sns")
                        XposedHelpers.callMethod(obj, "finish")
                    }
                })
                XposedHelpers.findAndHookMethod("com.tencent.mm.plugin.sns.ui.at", loader, "bho", object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val obj = param.thisObject
                        val activity = XposedHelpers.getObjectField(obj, "gcv") as Activity
                        val gson = Gson()

                        val m1 = XposedHelpers.getObjectField(obj, "mRf") as ArrayList<Any>
                        for (msg in m1) {
                            val msgCont = XposedHelpers.getObjectField(msg, "qaV") as Map<String, Any>
                            
                            // content parser
                            for (item in msgCont as Map<String, Any>) {
                                if (!snsMessageRecorder.contains(item.key)) {
                                    XposedBridge.log("SNS => ${gson.toJson(item.value).toString()}")
                                    snsMessageRecorder.add(item.key)
                                }
                            }
                        }
                        if (!taskFlag) {
                            activity.finish()
                            taskFlag = true
                        }
                        //XposedBridge.log("DBG -> trigger callback")
                    }
                })
            }
        })
    }
    
    private fun hookLog(loader: ClassLoader?) {
        
    }
}
