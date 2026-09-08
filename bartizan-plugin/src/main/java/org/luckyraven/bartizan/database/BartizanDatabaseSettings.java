package org.luckyraven.bartizan.database;

import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.keystone.persistence.database.DatabaseSettingsProvider;

public class BartizanDatabaseSettings implements DatabaseSettingsProvider {
	@Override
	public boolean isSqliteBackup() {
		return BartizanSettings.isSqliteBackup();
	}

	@Override
	public boolean isSqliteFailedMysql() {
		return BartizanSettings.isSqliteFailedMysql();
	}

	@Override
	public String getMysqlHost() {
		return BartizanSettings.getMysqlHost();
	}

	@Override
	public int getMysqlPort() {
		return BartizanSettings.getMysqlPort();
	}

	@Override
	public String getMysqlUsername() {
		return BartizanSettings.getMysqlUsername();
	}

	@Override
	public String getMysqlPassword() {
		return BartizanSettings.getMysqlPassword();
	}
}
