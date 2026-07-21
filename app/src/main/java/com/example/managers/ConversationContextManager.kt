package com.example.managers

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🧠 سياق المحادثة — يحتفظ بآخر 3 كيانات نشطة (تطبيق، جهة اتصال، مستوى صوت)
 * وبآخر إجراء قابل للتراجع، لتنفيذ الأوامر المبتورة («و الفيس») والتصحيحات
 * الفورية («ليس هذا»).
 */
@Singleton
class ConversationContextManager @Inject constructor() {

    enum class EntityKind { APP, CONTACT, VOLUME }

    /** كيان نشط في المحادثة: النوع، الاسم المعروض، وبيانات إضافية اختيارية. */
    data class ActiveEntity(
        val kind: EntityKind,
        val label: String,
        val payload: String? = null,
        val at: Long = System.currentTimeMillis()
    )

    /** آخر إجراء يمكن التراجع عنه عند تصحيح المستخدم «ليس هذا». */
    data class Undoable(
        val actionKey: String,
        val userText: String,
        val label: String,
        /** open_app: اسم الحزمة · volume: النسبة السابقة */
        val payload: String? = null,
        val at: Long = System.currentTimeMillis()
    )

    // ---------------- آخر 3 كيانات نشطة ----------------

    private val entities = ArrayDeque<ActiveEntity>()

    @Synchronized
    private fun push(entity: ActiveEntity) {
        entities.removeAll { it.kind == entity.kind && it.label.equals(entity.label, ignoreCase = true) }
        entities.addFirst(entity)
        while (entities.size > MAX_ACTIVE_ENTITIES) entities.removeLast()
    }

    fun pushApp(label: String, packageName: String) =
        push(ActiveEntity(EntityKind.APP, label, packageName))

    fun pushContact(name: String) =
        push(ActiveEntity(EntityKind.CONTACT, name, name))

    fun pushVolume(percent: Int) =
        push(ActiveEntity(EntityKind.VOLUME, "$percent بالمئة", percent.toString()))

    @Synchronized
    fun lastEntityOf(kind: EntityKind): ActiveEntity? = entities.firstOrNull { it.kind == kind }

    /** أحدث كيان ذُكر خلال [windowMs] — دليل على أن المحادثة ما زالت مستمرة. */
    @Synchronized
    fun lastEntityWithin(windowMs: Long): ActiveEntity? =
        entities.firstOrNull { System.currentTimeMillis() - it.at <= windowMs }

    @Synchronized
    fun entitiesSnapshot(): List<ActiveEntity> = entities.toList()

    // ---------------- آخر إجراء (عام) ----------------

    /** طابع زمني لآخر أمر نُفّذ بنجاح — يغذّي استكمال الأوامر المبتورة. */
    @Volatile
    var lastSuccessAt: Long = 0L
        private set

    fun noteSuccessfulAction() { lastSuccessAt = System.currentTimeMillis() }

    fun hasRecentAction(windowMs: Long): Boolean =
        lastSuccessAt > 0L && System.currentTimeMillis() - lastSuccessAt <= windowMs

    // ---------------- آخر إجراء قابل للتراجع ----------------

    @Volatile
    private var undoable: Undoable? = null

    fun setUndoable(undo: Undoable) { undoable = undo }

    fun lastUndoableFresh(windowMs: Long): Undoable? =
        undoable?.takeIf { System.currentTimeMillis() - it.at <= windowMs }

    fun clearUndoable() { undoable = null }

    companion object {
        private const val MAX_ACTIVE_ENTITIES = 3
    }
}
