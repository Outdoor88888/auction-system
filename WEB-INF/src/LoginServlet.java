import java.io.*;
import java.sql.*;
import java.util.Properties;
import javax.servlet.*;
import javax.servlet.http.*;

public class LoginServlet extends HttpServlet {
    private String _hostname, _dbname, _username, _password;

    public void init() throws ServletException {
        try {
            String iniFilePath = getServletConfig().getServletContext().getRealPath("WEB-INF/le4db.ini");
            Properties prop = new Properties(); prop.load(new FileInputStream(iniFilePath));
            _hostname = prop.getProperty("hostname"); _dbname = prop.getProperty("dbname");
            _username = prop.getProperty("username"); _password = prop.getProperty("password");
        } catch (Exception e) { e.printStackTrace(); }
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String action = request.getParameter("action");
        if ("logout".equals(action)) {
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            response.sendRedirect("login");
            return;
        }

        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();
        String error = (String) request.getAttribute("error");

        out.println("<html><head><style>body{font-family:sans-serif; text-align:center; padding-top:50px;}</style></head><body>");
        out.println("<h2>Webオークション ログイン</h2>");
        out.println("<form action='login' method='POST' onkeydown='if(event.key===\"Enter\"){event.preventDefault();}'>"); // Enter無効化
        if (error != null) out.println("<p style='color:red;'>" + error + "</p>");

        out.println("<div style='margin-bottom:10px;'>メールアドレス:<br><input type='text' name='email'></div>");
        out.println("<div style='margin-bottom:20px;'>パスワード:<br><input type='password' name='password'></div>");
        out.println("<button type='submit' onclick='this.form.submit()'>ログイン</button>"); // 明示的なSubmit
        out.println("</form>");
        out.println("<p><a href='submit'>新規ユーザ登録はこちら</a></p>");
        out.println("</body></html>");
    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        String email = request.getParameter("email");
        String pass = request.getParameter("password");

        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection("jdbc:postgresql://" + _hostname + ":5432/" + _dbname, _username, _password)) {
                PreparedStatement ps = conn.prepareStatement("SELECT * FROM Users WHERE email = ? AND password = ?");
                ps.setString(1, email);
                ps.setString(2, pass);
                ResultSet rs = ps.executeQuery();

                if (rs.next()) {
                    HttpSession session = request.getSession();
                    session.setAttribute("u_id", rs.getInt("id"));
                    session.setAttribute("name", rs.getString("name"));
                    // 通知用初期化
                    session.setAttribute("last_check_time", System.currentTimeMillis());
                    response.sendRedirect("itemList");
                } else {
                    request.setAttribute("error", "ログインに失敗しました");
                    doGet(request, response);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}