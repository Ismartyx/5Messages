package com.goodwy.messages.injection.android

import com.goodwy.messages.feature.backup.RestoreBackupService
import com.goodwy.messages.feature.webserver.WebClientService
import com.goodwy.messages.injection.scope.ActivityScope
import com.goodwy.messages.receiver.SendSmsReceiver
import com.goodwy.messages.service.AutoDeleteService
import com.goodwy.messages.service.HeadlessSmsSendService
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module
abstract class ServiceBuilderModule {

    @ActivityScope
    @ContributesAndroidInjector()
    abstract fun bindAutoDeleteService(): AutoDeleteService

    @ActivityScope
    @ContributesAndroidInjector()
    abstract fun bindHeadlessSmsSendService(): HeadlessSmsSendService

    @ActivityScope
    @ContributesAndroidInjector()
    abstract fun bindRestoreBackupService(): RestoreBackupService

    @ActivityScope
    @ContributesAndroidInjector()
    abstract fun bindWebClientService(): WebClientService

    @ActivityScope
    @ContributesAndroidInjector()
    abstract fun bindSendSmsReceiver(): SendSmsReceiver

}
