package smithereen;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import smithereen.model.Account;
import smithereen.model.User;
import smithereen.storage.DatabaseSchemaUpdater;
import smithereen.storage.MailStorage;
import smithereen.storage.SessionStorage;
import smithereen.storage.UserStorage;
import smithereen.storage.sql.DatabaseConnection;
import smithereen.storage.sql.DatabaseConnectionManager;
import smithereen.storage.sql.SQLQueryBuilder;

/**
 * Fills an empty local database with a few accounts and private messages so the UI has something to show.
 * Not part of the server. See TESTS.md for how to compile and run it.
 *
 * Creates: admin (owner), yulia, maria, dmitry. Every password is "password", e-mails are name@example.com.
 */
public class Seeder{
	public static void main(String[] args) throws Exception{
		if(args.length==0){
			System.err.println("Usage: Seeder <path to config.properties>");
			System.exit(1);
		}
		System.setProperty("user.timezone", "UTC");
		Config.load(args[0]);
		DatabaseSchemaUpdater.createSchemaIfEmpty();
		Config.loadFromDatabase();
		DatabaseSchemaUpdater.maybeUpdate();
		Config.reloadRoles();

		if(UserStorage.getLocalUserCount()>0){
			System.err.println("The database already has users. Seeding only works on an empty database.");
			System.exit(1);
		}

		SessionStorage.registerNewAccount("admin", "password", "admin@example.com", "Иван", "Петров", User.Gender.MALE);
		Account acc=SessionStorage.getAccountForUsernameAndPassword("admin@example.com", "password");
		UserStorage.setAccountRole(acc, 1, 0); // owner
		SessionStorage.registerNewAccount("yulia", "password", "yulia@example.com", "Юлия", "Куминова", User.Gender.FEMALE);
		SessionStorage.registerNewAccount("maria", "password", "maria@example.com", "Мария", "Соколова", User.Gender.FEMALE);
		SessionStorage.registerNewAccount("dmitry", "password", "dmitry@example.com", "Дмитрий", "Орлов", User.Gender.MALE);
		int me=UserStorage.getByUsername("admin").id;
		int yulia=UserStorage.getByUsername("yulia").id;
		int maria=UserStorage.getByUsername("maria").id;
		int dmitry=UserStorage.getByUsername("dmitry").id;

		// {sender, recipient, text, hours ago}
		Object[][] msgs={
				{dmitry, me, "Привет! Ты будешь на встрече в субботу?", 30},
				{me, dmitry, "Да, приду к семи", 29},
				{maria, me, "Скинь, пожалуйста, фотки с концерта", 26},
				{yulia, me, "Оооо да похоже на чат теперь!", 14},
				{yulia, me, "Урррааа", 14},
				{me, yulia, "Ну наконец-то :)", 13},
				{yulia, me, "Неее он хочет", 3},
				{yulia, me, "Он просто типа занятой", 3},
				{yulia, me, "И на стрессе", 3},
				{yulia, me, "Итд", 3},
				{yulia, me, "Позже зарегается говорит", 3},
				{yulia, me, "передала!", 0},
		};
		List<Long> readByMe=new ArrayList<>();
		for(Object[] m:msgs){
			int from=(Integer)m[0], to=(Integer)m[1];
			String text=(String)m[2];
			int hoursAgo=(Integer)m[3];
			Map<Integer, Long> ids=new HashMap<>(); // owner -> that owner's copy of the message
			MailStorage.createMessage("<p>"+text+"</p>", "", null, from, Set.of(to), null, new HashSet<>(Set.of(from, to)), Map.of(), ids);
			// Spread the messages over the last two days so the list shows different dates
			try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
				for(long id:ids.values()){
					PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "UPDATE mail_messages SET created_at=CURRENT_TIMESTAMP()-INTERVAL ? HOUR-INTERVAL ? MINUTE WHERE id=?", hoursAgo, hoursAgo==0 ? 26 : 7, id);
					stmt.execute();
				}
			}
			// Everything except today's burst from Yulia has already been read by the admin
			if(to==me && hoursAgo>5)
				readByMe.add(ids.get(me));
		}
		MailStorage.addMessageReadReceipt(me, readByMe, me);
		System.out.println("Seeded users: admin="+me+" yulia="+yulia+" maria="+maria+" dmitry="+dmitry);
	}
}
