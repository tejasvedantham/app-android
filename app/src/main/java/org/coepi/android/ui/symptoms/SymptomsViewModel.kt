package org.coepi.android.ui.symptoms

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers.mainThread
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.withLatestFrom
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.BehaviorSubject.createDefault
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.PublishSubject.create
import org.coepi.android.domain.model.Symptom
import org.coepi.android.domain.symptomflow.SymptomFlowManager
import org.coepi.android.domain.symptomflow.SymptomId
import org.coepi.android.extensions.rx.toIsInProgress
import org.coepi.android.extensions.rx.toLiveData
import org.coepi.android.extensions.toggle
import org.coepi.android.repo.SymptomRepo
import org.coepi.android.system.log.log
import org.coepi.android.tcn.TcnReportRepo
import org.coepi.android.ui.navigation.NavigationCommand.Back
import org.coepi.android.ui.navigation.RootNavigation

class SymptomsViewModel(
    symptomRepo: SymptomRepo,
    private val navigation: RootNavigation,
    private val symptomFlowManager: SymptomFlowManager,
    reportRepo: TcnReportRepo
) : ViewModel() {

    val isInProgress: LiveData<Boolean> = reportRepo.sendState
        .toIsInProgress()
        .observeOn(mainThread())
        .toLiveData()

    private val selectedSymptomIds: BehaviorSubject<Set<SymptomId>> =
        createDefault(emptySet())

    private val checkedSymptomTrigger: PublishSubject<SymptomViewData> = create()
    private val submitTrigger: PublishSubject<Unit> = create()

    private val symptomsObservable: Observable<List<SymptomViewData>> = Observables
        .combineLatest(symptomRepo.symptoms().toObservable(), selectedSymptomIds)
        .map { (symptoms, selectedIds) ->
            symptoms.map { it.toViewData(isChecked = selectedIds.contains(it.id)) }
        }

    val symptoms: LiveData<List<SymptomViewData>> = symptomsObservable.toLiveData()

    private val disposables = CompositeDisposable()

    private val selectedSymptoms = symptomsObservable
        .map { symptoms ->
            symptoms
                .filter { it.isChecked }
                .map { it.symptom }
        }

    init {
        disposables += checkedSymptomTrigger
            .withLatestFrom(selectedSymptomIds)
            .subscribe { (selectedSymptom, selectedIds) ->
                if (selectedSymptom.symptom.id == SymptomId.NONE && !selectedSymptom.isChecked) {
                    // "No symptoms" selected. Unselect all other symptoms that were selected/highlighted
                    selectedSymptomIds.onNext(emptySet<SymptomId>().toggle(selectedSymptom.symptom.id))
                } else if (selectedSymptom.symptom.id == SymptomId.NONE && selectedIds.size == 1) {
                    // "No symptoms" selected with no other symptoms highlighted. Only toggles NONE
                    selectedSymptomIds.onNext(selectedIds.toggle(selectedSymptom.symptom.id))
                } else {
                    selectedSymptomIds.onNext(
                        selectedIds.minus(SymptomId.NONE).toggle(selectedSymptom.symptom.id)
                    )
                }
            }

        disposables += submitTrigger
            .withLatestFrom(selectedSymptoms)
            .subscribe { (_, symptoms) ->
                symptomFlowManager.apply {
                    if (!startFlow(symptoms.map { it.id })) {
                        // TODO handle: Maybe don't enable button until something is selected
                        // TODO or ensure there's always a selection
                        log.e("Couldn't start flow")
                    }
                }
            }
    }

    fun onChecked(symptom: SymptomViewData) {
        checkedSymptomTrigger.onNext(symptom)
    }

    fun onSubmit() {
        submitTrigger.onNext(Unit)
    }

    fun onBack() {
        navigation.navigate(Back)
    }

    override fun onCleared() {
        super.onCleared()
        disposables.clear()
    }

    private fun Symptom.toViewData(isChecked: Boolean): SymptomViewData =
        SymptomViewData(name, isChecked, this)
}
