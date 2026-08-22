package com.naxor.app.data;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {CalculationEntity.class, ProductEntity.class, SaleEntity.class, DebtorEntity.class, DebtDetailEntity.class, ExpenseEntity.class, ProviderEntity.class, CustomerEntity.class, CashSessionEntity.class, PriceHistoryEntity.class, MovementLogEntity.class, BusinessDebtEntity.class, HotelRoomEntity.class, HotelBookingEntity.class, HotelRoomLayoutEntity.class, HotelChargeEntity.class, HotelPaymentEntity.class}, version = 31, exportSchema = false)
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
    public abstract HotelDao hotelDao();

    private static volatile AppDatabase INSTANCE;

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "asistente_comercial_db")
                            .fallbackToDestructiveMigration() // Activado temporalmente para esta actualización masiva
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
