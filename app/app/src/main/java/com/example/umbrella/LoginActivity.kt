package com.example.umbrella

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class LoginActivity : BaseActivity() {

    companion object {
        const val PREF_NAME      = "umbrella_prefs"
        const val KEY_KEEP_LOGIN = "keep_login"
        const val KEY_USER_ID    = "user_id"
        const val KEY_USER_PW    = "user_pw"
        const val KEY_TOKEN      = "jwt_token"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val etId   = findViewById<TextInputEditText>(R.id.etUserId)
        val etPw   = findViewById<TextInputEditText>(R.id.etPassword)
        val cbKeep = findViewById<CheckBox>(R.id.cbKeepLogin)

        if (intent.getBooleanExtra("SESSION_EXPIRED", false)) {
            Toast.makeText(this, "세션이 만료되었습니다. 다시 로그인해주세요.", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            val id = etId.text?.toString()?.trim() ?: ""
            val pw = etPw.text?.toString() ?: ""
            if (id.isNotEmpty() && pw.isNotEmpty()) performLogin(id, pw, cbKeep.isChecked)
            else Toast.makeText(this, "이메일과 비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
        }

        findViewById<TextView>(R.id.tvGoSignup).setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        tryAutoLogin()
    }

    // 저장된 토큰이 있으면 토큰으로 세션 복원을 시도하고,
    // 토큰이 없거나 만료됐으면 이메일/비밀번호로 재로그인한다.
    private fun tryAutoLogin() {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_KEEP_LOGIN, false)) return

        val savedToken = prefs.getString(KEY_TOKEN, "") ?: ""
        val savedId    = prefs.getString(KEY_USER_ID, "") ?: ""
        val savedPw    = prefs.getString(KEY_USER_PW, "") ?: ""

        if (savedToken.isNotEmpty()) {
            AppSession.jwtToken = savedToken
            Toast.makeText(this, "자동 로그인 중...", Toast.LENGTH_SHORT).show()
            verifyTokenAndLogin(savedId, savedPw)
        } else if (savedId.isNotEmpty() && savedPw.isNotEmpty()) {
            Toast.makeText(this, "자동 로그인 중...", Toast.LENGTH_SHORT).show()
            performLogin(savedId, savedPw, true)
        }
    }

    private fun verifyTokenAndLogin(savedId: String, savedPw: String) {
        RetrofitClient.service.getMe().enqueue(object : Callback<UserOut> {
            override fun onResponse(call: Call<UserOut>, response: Response<UserOut>) {
                if (response.isSuccessful) {
                    response.body()?.let { user ->
                        AppSession.isLoggedIn = true
                        AppSession.userId    = user.id.toString()
                        AppSession.userName  = user.name
                        AppSession.userPoint = user.balance
                        goMain()
                    }
                } else {
                    // 토큰 만료 시 이메일/비밀번호로 재로그인
                    AppSession.jwtToken = null
                    if (savedId.isNotEmpty() && savedPw.isNotEmpty()) {
                        performLogin(savedId, savedPw, true)
                    }
                }
            }
            override fun onFailure(call: Call<UserOut>, t: Throwable) {
                // 네트워크 오류 시에는 토큰을 유지한 채로 메인 진입 (오프라인 대응)
                if (!AppSession.jwtToken.isNullOrEmpty()) goMain()
            }
        })
    }

    private fun performLogin(id: String, pw: String, keepLogin: Boolean) {
        RetrofitClient.service.login(LoginRequest(email = id, password = pw, remember_me = keepLogin))
            .enqueue(object : Callback<LoginResponse> {
                override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                    if (response.isSuccessful) {
                        response.body()?.let { body ->
                            AppSession.syncLoginData(body, id)
                            saveLoginPrefs(keepLogin, id, pw, AppSession.jwtToken ?: "")
                            Toast.makeText(this@LoginActivity, "${body.user.name}님 환영합니다!", Toast.LENGTH_SHORT).show()
                            goMain()
                        }
                    } else {
                        val msg = if (response.code() == 401) "이메일 또는 비밀번호가 올바르지 않습니다."
                        else "로그인 실패 (${response.code()})"
                        Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                    Toast.makeText(this@LoginActivity, "네트워크 오류: 서버 상태를 확인하세요.", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun saveLoginPrefs(keepLogin: Boolean, id: String, pw: String, token: String) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().apply {
            putBoolean(KEY_KEEP_LOGIN, keepLogin)
            if (keepLogin) {
                putString(KEY_USER_ID, id)
                putString(KEY_USER_PW, pw)
                putString(KEY_TOKEN, token)
            } else {
                remove(KEY_USER_ID); remove(KEY_USER_PW); remove(KEY_TOKEN)
            }
            apply()
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
    }
}
