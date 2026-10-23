package com.gomezmart.app.data;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {CalculationEntity.class, ProductEntity.class, SaleEntity.class, DebtorEntity.class, DebtDetailEntity.class, ExpenseEntity.class, ProviderEntity.class, CustomerEntity.class, CashSessionEntity.class, PriceHistoryEntity.class, MovementLogEntity.class, BusinessDebtEntity.class, CommitmentEntity.class, HotelRoomEntity.class, HotelBookingEntity.class, HotelRoomLayoutEntity.class, HotelChargeEntity.class, HotelPaymentEntity.class, HotelMaintenanceEntity.class, HotelToolEntity.class, LoanClientEntity.class, LoanEntity.class, LoanInstallmentEntity.class, LoanExpenseEntity.class, LoanPaymentEntity.class, LoanBudgetEntity.class, LoanFundingSourceEntity.class, AccountEntity.class}, version = 57, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract CalculationDao calculationDao();
    public abstract ProductDao productDao();
    public abstract SaleDao saleDao();
    public abstract DebtorDao debtorDao();
    public abstract ExpenseDao expenseDao();
    public abstract ProviderDao providerDao();
    public abstract CustomerDao customerDao();
    public abstract CashDao cashDao();
    public abstract PriceHistoryDao priceHistoryDao();
    public abstract MovementLogDao movementLogDao();
    public abstract BusinessDebtDao businessDebtDao();
    public abstract CommitmentDao commitmentDao();
    public abstract HotelDao hotelDao();
    public abstract HotelToolDao hotelToolDao();
    public abstract LoanDao loanDao();
    public abstract AccountDao accountDao();
    public abstract SystemMaintenanceDao systemMaintenanceDao();

    private static volatile AppDatabase INSTANCE;

    public static final Migration MIGRATION_48_49 = new Migration(48, 49) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE loans ADD COLUMN isPersonal INTEGER NOT NULL DEFAULT 0");
            } catch (Exception e) {
                // Si la columna ya existe por alguna razón, ignorar
            }
        }
    };

    public static final Migration MIGRATION_49_50 = new Migration(49, 50) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE loans ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0");
            } catch (Exception e) {
                // Si la columna ya existe por alguna razón, ignorar
            }
        }
    };

    public static final Migration MIGRATION_50_51 = new Migration(50, 51) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE loan_payments ADD COLUMN receiptPhotoPath TEXT");
            } catch (Exception e) {
                // Si la columna ya existe por alguna razón, ignorar
            }
        }
    };

    public static final Migration MIGRATION_51_52 = new Migration(51, 52) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE movement_logs ADD COLUMN referenceId TEXT");
            } catch (Exception e) {
                // Si la columna ya existe por alguna razón, ignorar
            }
        }
    };

    public static final Migration MIGRATION_52_53 = new Migration(52, 53) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE movement_logs ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0");
            } catch (Exception e) {
                // Si la columna ya existe por alguna razón, ignorar
            }
        }
    };

    public static final Migration MIGRATION_53_54 = new Migration(53, 54) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            try {
                database.execSQL("CREATE TABLE IF NOT EXISTS `loan_funding_sources` (`loanId` TEXT NOT NULL, `isPersonal` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`loanId`))");
            } catch (Exception e) {
                // Si la tabla ya existe por alguna razón, ignorar
            }
        }
    };

    public static final Migration MIGRATION_54_55 = new Migration(54, 55) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            try {
                database.execSQL("CREATE TABLE IF NOT EXISTS `accounts` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `bankName` TEXT NOT NULL, `accountNumber` TEXT NOT NULL, `balance` REAL NOT NULL, `accountType` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `isSynced` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            } catch (Exception e) {}
        }
    };

    public static final Migration MIGRATION_55_56 = new Migration(55, 56) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE loan_expenses ADD COLUMN accountId TEXT NOT NULL DEFAULT ''");
                database.execSQL("ALTER TABLE loan_expenses ADD COLUMN accountName TEXT NOT NULL DEFAULT ''");
                database.execSQL("ALTER TABLE loan_payments ADD COLUMN accountId TEXT NOT NULL DEFAULT ''");
                database.execSQL("ALTER TABLE loan_payments ADD COLUMN accountName TEXT NOT NULL DEFAULT ''");
                database.execSQL("ALTER TABLE loan_budgets ADD COLUMN includedAccountIds TEXT NOT NULL DEFAULT ''");
            } catch (Exception e) {}
        }
    };

    public static final Migration MIGRATION_56_57 = new Migration(56, 57) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE accounts ADD COLUMN origin TEXT NOT NULL DEFAULT 'BUSINESS'");
            } catch (Exception e) {}
        }
    };

    public static final Migration MIGRATION_57_58 = new Migration(57, 58) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE loan_expenses ADD COLUMN tipoCuenta TEXT NOT NULL DEFAULT ''");
                database.execSQL("ALTER TABLE loan_payments ADD COLUMN montoAbonado REAL NOT NULL DEFAULT 0.0");
            } catch (Exception e) {}
        }
    };

    private static final java.util.concurrent.atomic.AtomicReference<Context> context_holder =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Callback que deja constancia de si la base de datos local tuvo que ser
     * RECREADA (por `fallbackToDestructiveMigration`). Esto ocurre cuando la
     * versión instalada de la app es muy antigua y no existe un camino de
     * migración válido. Sin este aviso, el usuario perdería sus datos locales
     * sin saber por qué.
     */
    private static final RoomDatabase.Callback DB_CALLBACK = new RoomDatabase.Callback() {
        @Override
        public void onCreate(@NonNull SupportSQLiteDatabase db) {
            super.onCreate(db);
            try {
                Context ctx = context_holder.get();
                if (ctx != null) {
                    ctx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("db_was_recreated", true).apply();
                }
            } catch (Exception ignored) { }
        }
    };

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    context_holder.set(context.getApplicationContext());
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "asistente_comercial_db")
                            .addMigrations(MIGRATION_48_49, MIGRATION_49_50, MIGRATION_50_51, MIGRATION_51_52, MIGRATION_52_53, MIGRATION_53_54, MIGRATION_54_55, MIGRATION_55_56, MIGRATION_56_57, MIGRATION_57_58)
                            // IMPORTANTE: Este "fallback" evita que la app CRASHEE al
                            // actualizar desde una versión muy antigua, pero BORRA la base
                            // local. Por eso DB_CALLBACK deja constancia y la app avisa
                            // al usuario. Cada vez que se sube la versión de la base hay
                            // que añadir la migración correspondiente (MIGRATION_X_Y).
                            .fallbackToDestructiveMigration(true)
                            .addCallback(DB_CALLBACK)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
