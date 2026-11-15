# soundgen

basic 사운드팩을 수식으로 합성한다. 표준 라이브러리만 쓰고 난수 시드가 고정이라 결과가 항상 같다.

```
python3 tools/soundgen/generate.py      # assets/soundpacks/basic/*.wav 재생성
python3 tools/soundgen/verify.py        # 형식·이름·음높이 검증 (실패 시 종료 코드 1)
```

## 샘플 이름 규약

파일명(확장자 제외)이 sampleId이며 앱 코드가 이 이름에 의존한다. 바꾸지 않는다.

- `drum_{kick,snare,hihat,clap}`
- `bass_{도수}_o{옥타브}`: 도수 1,4,5(C 장조의 C,F,G), 옥타브 1,2,3 (o1=C2대, o2=C3대, o3=C4대)
- `mel_{0..7}`: C 장조 펜타토닉 C4 D4 E4 G4 A4 C5 D5 E5
- `fx_{impact,riser,sweep,zap}`
- `metro_{hi,lo}`: 강박/약박

형식은 48000Hz / 16bit / mono, 샘플당 1.0초 이하, 피크 0.85.

## car 팩 (차량 사운드팩)

```
python3 tools/soundgen/generate_car.py      # assets/soundpacks/car/*.wav 재생성 (basic과 sampleId 동일)
python3 tools/soundgen/verify.py all        # 모든 팩 검증 (verify.py car 처럼 팩 하나만도 가능)
```

도어·트렁크·방향지시등 릴레이·안전벨트 버클(드럼), 경적 음색(베이스·멜로디), 경적·시동음·경고 차임·후방 경고음(FX)을 수식으로 합성한다. 메트로놈도 방향지시등 "딱/똑"이다.

