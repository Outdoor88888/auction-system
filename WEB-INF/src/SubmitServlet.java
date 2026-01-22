import java.io.*;
import java.sql.*;
import java.util.Calendar;
import java.util.Properties;
import javax.servlet.*;
import javax.servlet.http.*;

public class SubmitServlet extends HttpServlet {
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
        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();
        HttpSession session = request.getSession(false);
        String mode = request.getParameter("mode");
        boolean isEdit = "edit".equals(mode) && session != null && session.getAttribute("u_id") != null;

        String error = (String) request.getAttribute("error");
        String problemField = (String) request.getAttribute("problemField");

        String name = "", pass = "", real_name = "", email = "", address = "";
        String b_year = "", b_month = "", b_day = "";

        // 編集モードなら既存データを取得 (SELECT)
        boolean dataLoaded = false;
        if (isEdit && error == null) {
            try {
                Class.forName("org.postgresql.Driver");
                try (Connection conn = DriverManager.getConnection("jdbc:postgresql://" + _hostname + ":5432/" + _dbname, _username, _password)) {
                    if (session != null) AuctionHelper.processNotifications(out, session, conn);
                    PreparedStatement ps = conn.prepareStatement("SELECT * FROM Users WHERE id = ?");
                    ps.setInt(1, (Integer)session.getAttribute("u_id"));
                    ResultSet rs = ps.executeQuery();
                    if(rs.next()){
                        name = rs.getString("name"); pass = rs.getString("password");
                        real_name = rs.getString("real_name"); email = rs.getString("email");
                        address = rs.getString("address");
                        Date bd = rs.getDate("birth_date");
                        if(bd != null) {
                            Calendar cal = Calendar.getInstance(); cal.setTime(bd);
                            b_year = String.valueOf(cal.get(Calendar.YEAR));
                            b_month = String.valueOf(cal.get(Calendar.MONTH) + 1);
                            b_day = String.valueOf(cal.get(Calendar.DAY_OF_MONTH));
                        }
                        dataLoaded = true;
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }

        if (!dataLoaded) {
            name = request.getParameter("name"); if(name==null) name="";
            pass = request.getParameter("password"); if(pass==null) pass="";
            real_name = request.getParameter("real_name"); if(real_name==null) real_name="";
            email = request.getParameter("email"); if(email==null) email="";
            address = request.getParameter("address"); if(address==null) address="";
            b_year = request.getParameter("year"); b_month = request.getParameter("month"); b_day = request.getParameter("day");
        }

        if (isEdit) AuctionHelper.printHeader(out, session, "submit");
        else out.println("<html><head><script>function stopEnter(e){if(e.key==='Enter'){e.preventDefault();return false;}}</script></head><body>");

        out.println("<h2>" + (isEdit ? "登録情報変更" : "ユーザー登録") + "</h2>");
        if (error != null) out.println("<p style='color:red;'>エラー: " + error + "</p>");

        out.println("<form action='submit' method='POST' onkeydown='stopEnter(event)'>");
        if (isEdit) out.println("<input type='hidden' name='mode' value='edit'>");
        out.println("ユーザー名: <input type='text' name='name' value='" + name + "' style='" + ("name".equals(problemField)?"background:pink":"") + "'><br>");
        out.println("パスワード: <input type='password' name='password' value='" + pass + "' style='" + ("password".equals(problemField)?"background:pink":"") + "'><br>");
        out.println("本名: <input type='text' name='real_name' value='" + real_name + "' style='" + ("real_name".equals(problemField)?"background:pink":"") + "'><br>");

        out.println("生年月日: <select name='year'>");
        for(int y=1950; y<=2025; y++) out.println("<option value='"+y+"' "+(String.valueOf(y).equals(b_year)?"selected":"")+">"+y+"</option>");
        out.println("</select>年 <select name='month'>");
        for(int m=1; m<=12; m++) out.println("<option value='"+m+"' "+(String.valueOf(m).equals(b_month)?"selected":"")+">"+m+"</option>");
        out.println("</select>月 <select name='day'>");
        for(int d=1; d<=31; d++) out.println("<option value='"+d+"' "+(String.valueOf(d).equals(b_day)?"selected":"")+">"+d+"</option>");
        out.println("</select>日<br>");

        out.println("メールアドレス: <input type='text' name='email' value='" + email + "' style='" + ("email".equals(problemField)?"background:pink":"") + "'><br>");
        out.println("住所: <input type='text' name='address' value='" + address + "' style='" + ("address".equals(problemField)?"background:pink":"") + "'><br>");
        out.println("<br><button type='button' onclick='this.form.submit()'>" + (isEdit ? "変更" : "登録") + "</button>");
        out.println("</form>");
        if (!isEdit) out.println("<a href='login'>ログイン画面へ戻る</a>");
        AuctionHelper.printFooter(out);
    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        String mode = request.getParameter("mode");
        HttpSession session = request.getSession(false);
        boolean isEdit = "edit".equals(mode);

        String name = request.getParameter("name");
        String password = request.getParameter("password");
        String real_name = request.getParameter("real_name");
        String email = request.getParameter("email");
        String address = request.getParameter("address");
        String year = request.getParameter("year");
        String month = request.getParameter("month");
        String day = request.getParameter("day");
        String birth_date = year + "-" + month + "-" + day;

        if (isEmpty(name)) returnError(request, response, "ユーザー名は必須です", "name");
        else if (isEmpty(password)) returnError(request, response, "パスワードは必須です", "password");
        else if (isEmpty(real_name)) returnError(request, response, "本名は必須です", "real_name");
        else if (isEmpty(email)) returnError(request, response, "メールアドレスは必須です", "email");
        else if (isEmpty(address)) returnError(request, response, "住所は必須です", "address");
        else {
            try {
                Class.forName("org.postgresql.Driver");
                try (Connection conn = DriverManager.getConnection("jdbc:postgresql://" + _hostname + ":5432/" + _dbname, _username, _password)) {
                    // 重複チェック
                    String checkSql = "SELECT id FROM Users WHERE email = ?";
                    if (isEdit) checkSql += " AND id <> " + session.getAttribute("u_id");
                    PreparedStatement psCheck = conn.prepareStatement(checkSql);
                    psCheck.setString(1, email);
                    if (psCheck.executeQuery().next()) {
                        returnError(request, response, "そのメールアドレスは既に使用されています", "email");
                        return;
                    }
                    if (isEdit) {
                        // 更新 (UPDATE)
                        PreparedStatement ps = conn.prepareStatement("UPDATE Users SET name=?, password=?, real_name=?, birth_date=?, email=?, address=? WHERE id=?");
                        ps.setString(1, name); ps.setString(2, password); ps.setString(3, real_name);
                        ps.setDate(4, Date.valueOf(birth_date)); ps.setString(5, email); ps.setString(6, address);
                        ps.setInt(7, (Integer)session.getAttribute("u_id"));
                        ps.executeUpdate();
                        response.setContentType("text/html;charset=UTF-8");
                        response.getWriter().println("<script>alert('変更が完了しました'); location.href='userDetail?uid="+session.getAttribute("u_id")+"';</script>");
                    } else {
                        // 新規登録 (INSERT)
                        PreparedStatement ps = conn.prepareStatement("INSERT INTO Users (name, password, real_name, birth_date, email, address) VALUES (?, ?, ?, ?, ?, ?)");
                        ps.setString(1, name); ps.setString(2, password); ps.setString(3, real_name);
                        ps.setDate(4, Date.valueOf(birth_date)); ps.setString(5, email); ps.setString(6, address);
                        ps.executeUpdate();
                        response.setContentType("text/html;charset=UTF-8");
                        response.getWriter().println("<script>alert('登録が完了しました。ログインしてください。'); location.href='login';</script>");
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                returnError(request, response, "DBエラー: " + e.getMessage(), "all");
            }
        }
    }
    private boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
    private void returnError(HttpServletRequest req, HttpServletResponse res, String msg, String field) throws ServletException, IOException {
        req.setAttribute("error", msg); req.setAttribute("problemField", field); doGet(req, res);
    }
}