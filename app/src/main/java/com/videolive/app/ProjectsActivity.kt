package com.videolive.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.videolive.app.data.ProjectStore
import com.videolive.app.data.ThemeStore
import com.videolive.app.stream.StreamService
import com.videolive.app.util.ThemeEngine

/**
 * Premium home screen: persistent Stream Projects.
 *
 * Projects are stored by [ProjectStore] and survive app restarts. Opening a
 * project loads its exact saved configuration (video, destination, key,
 * quality, fps, bitrate, volumes, mic, loop) into the dashboard. Only ONE
 * live session can run app-wide — if a stream is live, the banner routes the
 * user to it instead of allowing a second encoder/RTMP session.
 */
class ProjectsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var liveBanner: TextView

    /** The project currently loaded in the dashboard (if any). */
    private var activeProjectId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeEngine.apply(this)
        setContentView(R.layout.activity_projects)

        container = findViewById(R.id.projectsContainer)
        emptyHint = findViewById(R.id.emptyProjectsHint)
        liveBanner = findViewById(R.id.liveBannerProjects)

        findViewById<ImageButton>(R.id.btnThemeProjects).setOnClickListener { openThemePicker() }
        findViewById<TextView>(R.id.btnCreateStream).setOnClickListener { createProject() }
        liveBanner.setOnClickListener { startActivity(Intent(this, LiveActivity::class.java)) }

        activeProjectId = getSharedPreferences("vl_project_session", MODE_PRIVATE)
            .getString("active_id", null)
    }

    override fun onResume() {
        super.onResume()
        liveBanner.visibility = if (StreamService.isStreaming) TextView.VISIBLE else TextView.GONE
        render()
    }

    private fun render() {
        container.removeAllViews()
        val projects = ProjectStore.list(this)
        emptyHint.visibility = if (projects.isEmpty()) TextView.VISIBLE else TextView.GONE

        projects.forEach { p ->
            container.addView(buildCard(p))
        }
    }

    private fun buildCard(p: ProjectStore.Project): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val name = TextView(this).apply {
            text = p.name
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ThemeEngine.color(this@ProjectsActivity, R.attr.vlTextPrimary))
        }
        val video = TextView(this).apply {
            text = p.videoName
            textSize = 12.5f
            setTextColor(ThemeEngine.color(this@ProjectsActivity, R.attr.vlTextSecondary))
        }
        val status = TextView(this).apply {
            val live = StreamService.isStreaming && p.id == activeProjectId
            text = when {
                live -> "● LIVE now"
                p.lastStatus.isEmpty() -> "Never streamed"
                else -> "Last session: ${p.lastStatus}"
            }
            textSize = 12f
            setTextColor(
                if (live) ThemeEngine.color(this@ProjectsActivity, R.attr.vlAccent)
                else ThemeEngine.color(this@ProjectsActivity, R.attr.vlTextFaint)
            )
            if (live) setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(actionButton("OPEN", accent = true) { openProject(p) })
        actions.addView(actionButton("RENAME", accent = false) { renameProject(p) })
        actions.addView(actionButton("DUPLICATE", accent = false) {
            ProjectStore.duplicate(this, p.id)
            render()
            toast("Project duplicated")
        })
        actions.addView(actionButton("DELETE", accent = false) { confirmDelete(p) })

        card.addView(name)
        card.addView(video)
        card.addView(status)
        card.addView(actions)
        card.setOnClickListener { openProject(p) }
        return card
    }

    private fun actionButton(label: String, accent: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(
                if (accent) ThemeEngine.color(this@ProjectsActivity, R.attr.vlAccent)
                else ThemeEngine.color(this@ProjectsActivity, R.attr.vlTextSecondary)
            )
            setPadding(0, dp(8), dp(18), dp(8))
            setOnClickListener { onClick() }
        }

    private fun openProject(p: ProjectStore.Project) {
        if (StreamService.isStreaming && p.id != activeProjectId) {
            // Single-active-stream policy: never start a second encoder/RTMP
            // session. Offer the live dashboard instead.
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.projects_already_live_title)
                .setMessage(R.string.projects_already_live_body)
                .setPositiveButton(R.string.projects_open_live) { _, _ ->
                    startActivity(Intent(this, LiveActivity::class.java))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        ProjectStore.applyToGlobal(this, p.id)
        getSharedPreferences("vl_project_session", MODE_PRIVATE)
            .edit().putString("active_id", p.id).apply()
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_PROJECT_ID, p.id)
            .putExtra(MainActivity.EXTRA_PROJECT_NAME, p.name)
        startActivity(intent)
    }

    private fun createProject() {
        val input = EditText(this).apply {
            hint = getString(R.string.project_name_hint)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_stream)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val p = ProjectStore.create(this, input.text.toString())
                openProject(p)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renameProject(p: ProjectStore.Project) {
        val input = EditText(this).apply {
            setText(p.name)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_project)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                ProjectStore.rename(this, p.id, input.text.toString())
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(p: ProjectStore.Project) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_project)
            .setMessage(getString(R.string.delete_project_body, p.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                // Deleting a project never stops a running stream.
                ProjectStore.delete(this, p.id)
                if (activeProjectId == p.id) {
                    getSharedPreferences("vl_project_session", MODE_PRIVATE)
                        .edit().remove("active_id").apply()
                    activeProjectId = null
                }
                render()
                toast("Project deleted")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openThemePicker() {
        val themes = ThemeStore.Theme.values()
        val current = ThemeStore.current(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.theme_title)
            .setSingleChoiceItems(
                themes.map { it.label }.toTypedArray(),
                themes.indexOf(current)
            ) { dialog, which ->
                dialog.dismiss()
                ThemeStore.saveTheme(this, themes[which])
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
