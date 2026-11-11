package com.gk.reader

data class Category(val key: String, val label: String, val count: Int)

data class LastLesson(val lesson: Int, val title: String, val at: Long)

data class CourseSummary(
    val id: String,
    val name: String,
    val author: String,
    val identity: String,
    val category: String,
    val categoryLabel: String,
    val badge: String,
    val learners: String,
    val total: Int,
    val coverUrl: String,
    val read: Int,
    val pct: Int,
    val started: Boolean,
    val last: LastLesson?
)

data class CoursesPayload(val categories: List<Category>, val courses: List<CourseSummary>)

data class LessonItem(
    val idx: Int,
    val label: String,
    val date: String,
    val dur: String,
    val hasAudio: Boolean,
    val hasPdf: Boolean,
    val audioUrl: String,
    val pdfUrl: String,
    val read: Boolean,
    val pct: Double,
    val pos: Double
)

data class Chapter(val name: String, val items: List<LessonItem>)

data class CourseDetail(
    val id: String,
    val name: String,
    val author: String,
    val identity: String,
    val badge: String,
    val learners: String,
    val coverUrl: String,
    val total: Int,
    val read: Int,
    val pct: Int,
    val chapters: List<Chapter>,
    val last: LastLesson?
) {
    val lessons: List<LessonItem> get() = chapters.flatMap { it.items }
}

data class NavRef(val idx: Int, val title: String)

data class LessonDetail(
    val idx: Int,
    val total: Int,
    val kind: String,
    val title: String,
    val course: String,
    val author: String,
    val date: String,
    val duration: String,
    val coverUrl: String,
    val audioUrl: String,
    val pdfUrl: String,
    val bodyHtml: String,
    val prev: NavRef?,
    val next: NavRef?
)

data class LessonProgress(
    val pct: Double,
    val pos: Double,
    val dur: Double,
    val read: Boolean,
    val touched: Long
)

data class CourseProgress(val lessons: Map<Int, LessonProgress>, val last: LastLesson?)

data class ContinueRow(
    val id: String,
    val name: String,
    val coverUrl: String,
    val lesson: Int,
    val title: String,
    val pct: Double,
    val at: Long
)

data class RecentRow(
    val id: String,
    val name: String,
    val coverUrl: String,
    val lesson: Int,
    val title: String,
    val pct: Double,
    val read: Boolean,
    val at: Long
)

data class Stats(
    val lessonsRead: Int,
    val lessonsTotal: Int,
    val minutes: Int,
    val coursesStarted: Int,
    val coursesTotal: Int,
    val daysRead: Int,
    val streak: Int,
    val continueRows: List<ContinueRow>,
    val recent: List<RecentRow>
)

data class SearchPayload(val courses: List<CourseSummary>, val lessons: List<LessonHit>)

data class LessonHit(
    val courseId: String,
    val courseName: String,
    val idx: Int,
    val label: String,
    val chapter: String,
    val date: String,
    val dur: String
)

data class Ping(val ok: Boolean, val name: String, val version: Int, val authenticated: Boolean, val courses: Int)

data class QueueItem(
    val courseId: String,
    val courseName: String,
    val coverUrl: String,
    val idx: Int,
    val title: String,
    val audioUrl: String
)
