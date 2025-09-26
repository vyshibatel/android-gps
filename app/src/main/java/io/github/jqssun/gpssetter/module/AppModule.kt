package io.github.jqssun.gpssetter.module

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jqssun.gpssetter.api.ElevationService
import io.github.jqssun.gpssetter.api.RoutingService
import io.github.jqssun.gpssetter.module.util.ApplicationScope
import io.github.jqssun.gpssetter.room.AppDatabase
import io.github.jqssun.gpssetter.room.FavoriteDao
import io.github.jqssun.gpssetter.update.GitHubService
import io.github.jqssun.gpssetter.utils.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule{

    @Singleton
    @Provides
    @Named("GitHub")
    fun createGitHubService(): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.github.com/repos/jqssun/android-gps-setter/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Singleton
    @Provides
    @Named("Elevation")
    fun createElevationService(): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.open-elevation.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Singleton
    @Provides
    fun provideDownloadManger(application: Application) =
        application.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager


    @Singleton
    @Provides
    fun provideGithubService(@Named("GitHub") retrofit: Retrofit): GitHubService =
        retrofit.create(GitHubService::class.java)

    @Singleton
    @Provides
    fun provideElevationService(@Named("Elevation") retrofit: Retrofit): ElevationService =
        retrofit.create(ElevationService::class.java)

    @Singleton
    @Provides
    fun provideRoutingService(): RoutingService =
        RoutingService()

    @Provides
    @Singleton
    fun provideDatabase(application: Application, callback: AppDatabase.Callback)
            = Room.databaseBuilder(application, AppDatabase::class.java, "user_database")
        .allowMainThreadQueries()
        .fallbackToDestructiveMigration()
        .addCallback(callback)
        .build()


    @Singleton
    @Provides
    fun providesUserDao(favoriteDatabase: AppDatabase) : FavoriteDao =
        favoriteDatabase.favoriteDao()

    @Singleton
    @Provides
    fun provideSettingRepo() : PrefManager =
        PrefManager

    @ApplicationScope
    @Provides
    @Singleton
    fun providesApplicationScope() = CoroutineScope(SupervisorJob())

}