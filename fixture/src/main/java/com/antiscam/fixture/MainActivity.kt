package com.antiscam.fixture

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** A separate app that emits normal text-change events without accounts or a network. */
class MainActivity : Activity() {
    private var passwordField = false
    private var blockScreenshots = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        passwordField = savedInstanceState?.getBoolean("passwordField") ?: false
        blockScreenshots = savedInstanceState?.getBoolean("blockScreenshots") ?: false
        if (blockScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(245, 248, 246))
        }
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(dp(24) + bars.left, dp(24) + bars.top, dp(24) + bars.right, dp(24) + bars.bottom)
            insets
        }
        fun text(value: String, size: Float, color: Int = Color.rgb(20, 38, 33)): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                setPadding(0, 0, 0, dp(16))
            }

        content.addView(text("Test conversation", 26f))
        content.addView(text("Synthetic content for the AntiScam capture test.", 15f))
        content.addView(text("Parcel support", 17f, Color.rgb(0, 107, 94)))
        content.addView(text("Your parcel is waiting. Pay a delivery fee of 49 dollars. Keep this payment secret.", 23f))
        val reply = EditText(this).apply {
            id = REPLY_FIELD_ID
            hint = "Type a reply"
            contentDescription = "Type a reply"
            textSize = 20f
            setTextColor(Color.rgb(20, 38, 33))
            setHintTextColor(Color.rgb(90, 102, 97))
            inputType = replyInputType()
            maxLines = 3
            isSaveEnabled = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        content.addView(reply, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(text("Type a few letters. Wait at least three seconds. The first scan can take longer. Return to AntiScam to see the extracted text.", 15f).apply {
            setPadding(0, dp(16), 0, 0)
        })
        content.addView(CheckBox(this).apply {
            text = "Password field"
            textSize = 16f
            minHeight = dp(48)
            isChecked = passwordField
            setOnCheckedChangeListener { _, checked ->
                passwordField = checked
                reply.inputType = replyInputType()
                reply.text.clear()
            }
        })
        content.addView(CheckBox(this).apply {
            text = "Block screenshots"
            textSize = 16f
            minHeight = dp(48)
            isChecked = blockScreenshots
            setOnCheckedChangeListener { _, checked ->
                blockScreenshots = checked
                if (checked) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        })
        content.addView(text("Password typing must not trigger capture. Block screenshots tests the protected-screen error.", 13f))
        content.addView(Button(this).apply {
            text = "Return to AntiScam"
            setOnClickListener {
                try {
                    startActivity(Intent().apply {
                        setClassName("com.antiscam.app", "com.antiscam.app.MainActivity")
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    })
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "Install AntiScam first.", Toast.LENGTH_LONG).show()
                } catch (_: SecurityException) {
                    Toast.makeText(this@MainActivity, "Android could not open AntiScam. Open it from the home screen.", Toast.LENGTH_LONG).show()
                }
            }
        })
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("passwordField", passwordField)
        outState.putBoolean("blockScreenshots", blockScreenshots)
        super.onSaveInstanceState(outState)
    }

    private fun replyInputType(): Int = InputType.TYPE_CLASS_TEXT or
        if (passwordField) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

    private companion object {
        const val REPLY_FIELD_ID = 1001
    }
}
