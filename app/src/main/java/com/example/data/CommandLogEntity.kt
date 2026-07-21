package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * مركز الأوامر — سجل كامل لكل أمر نفذه المساعد.
 * كل البيانات محفوظة محلياً 100% على الجهاز (Room).
 */
@Entity(tableName = "command_logs")
data class CommandLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,

    /** النص الذي قاله/كتبه المستخدم. */
    val commandText: String,

    /** تاريخ ووقت التنفيذ (epoch millis). */
    val timestamp: Long,

    /** "SUCCESS" أو "FAILED" — هل نجح التنفيذ؟ */
    val status: String,

    /** رسالة المساعد للمستخدم (رد التنفيذ). */
    val reason: String? = null,

    /** الأمر الذي فهمه التطبيق فعلياً (للعبارات المتعلّمة: الأمر الصحيح المحفوظ). */
    val understoodCommand: String? = null,

    /** نسبة الثقة في الفهم 0.0..1.0 */
    val confidence: Float = 0f,

    /** الإجراء الذي تم تنفيذه (تسمية القدرة المنفذة: فتح تطبيق، مكالمة، ...) */
    val actionTaken: String? = null,

    /** سبب الفشل عند عدم النجاح. */
    val failReason: String? = null,

    /** وقت تنفيذ الأمر بالمللي ثانية. */
    val durationMs: Long = 0L
)
