import SwiftUI

struct LoginView: View {
    @ObservedObject var session: SessionStore
    @State private var email = ""
    @State private var password = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("邮箱", text: $email)
                        .textContentType(.username)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("密码", text: $password)
                        .textContentType(.password)
                } header: {
                    Text("登录 WAO")
                } footer: {
                    Text("原生客户端直接连接 WAO 真实接口，不保存明文密码。首期使用已绑定的邮箱密码登录。")
                }

                if let message = session.errorMessage {
                    Section {
                        Label(message, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    Button {
                        Task { await session.login(email: email, password: password) }
                    } label: {
                        HStack {
                            Spacer()
                            if session.isSubmitting {
                                ProgressView()
                            } else {
                                Text("登录")
                            }
                            Spacer()
                        }
                    }
                    .disabled(session.isSubmitting || email.isEmpty || password.isEmpty)
                }
            }
            .navigationTitle("WAO")
        }
    }
}
