package com.example.umbrella

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SignupActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        val etId = findViewById<TextInputEditText>(R.id.etSignupId)
        val etName = findViewById<TextInputEditText>(R.id.etSignupName)
        val etPw = findViewById<TextInputEditText>(R.id.etSignupPassword)
        val etPwConfirm = findViewById<TextInputEditText>(R.id.etSignupPasswordConfirm)
        val btnSignup = findViewById<Button>(R.id.btnDoSignup)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        btnSignup.setOnClickListener {
            val email = etId.text.toString().trim()
            val name = etName.text.toString().trim()
            val pw = etPw.text.toString()
            val pwConfirm = etPwConfirm.text.toString()

            if (email.isEmpty() || name.isEmpty() || pw.isEmpty()) {
                Toast.makeText(this, "모든 정보를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pw != pwConfirm) {
                Toast.makeText(this, "비밀번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSignup.isEnabled = false

            val request = SignupRequest(email = email, name = name, password = pw, password_confirm = pwConfirm)
            RetrofitClient.service.signup(request).enqueue(object : Callback<LoginResponse> {
                override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                    btnSignup.isEnabled = true
                    if (response.isSuccessful) {
                        response.body()?.let { body ->
                            AppSession.syncLoginData(body, email)
                            Toast.makeText(this@SignupActivity, "가입 완료! ${body.user.name}님 환영합니다.", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this@SignupActivity, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            })
                        }
                    } else {
                        val msg = when (response.code()) {
                            409 -> "이미 가입된 이메일입니다."
                            422 -> "입력 형식을 확인해주세요."
                            else -> "가입 실패 (${response.code()})"
                        }
                        Toast.makeText(this@SignupActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                    btnSignup.isEnabled = true
                    Toast.makeText(this@SignupActivity, "네트워크 오류: 서버를 확인하세요.", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }
}
