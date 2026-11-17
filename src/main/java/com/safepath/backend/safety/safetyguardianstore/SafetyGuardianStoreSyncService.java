package com.safepath.backend.safety.safetyguardianstore;
import java.net.URI; import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.safepath.backend.safety.dto.SafetyFacilityImportItem;
import com.safepath.backend.safety.service.SafetyFacilitySyncService;
import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient; import org.springframework.web.util.UriComponentsBuilder;
@Service public class SafetyGuardianStoreSyncService {
 private static final String ENDPOINT="https://api.data.go.kr/openapi/tn_pubr_public_female_safety_prtchouse_api", SOURCE="SAFETY_GUARDIAN_STORE"; private final RestClient client=RestClient.create(); private final String key; private final SafetyFacilitySyncService facilities;
 public SafetyGuardianStoreSyncService(@Value("${SAFEPATH_SAFETY_GUARDIAN_STORE_API_KEY:}") String key,SafetyFacilitySyncService facilities){this.key=key;this.facilities=facilities;}
 public SyncResult syncAll(int maxPages){int created=0,updated=0,total=0; for(int page=1;;page++){Response r=fetch(page); if(r.header()==null||!"00".equals(r.header().resultCode())||r.body()==null||r.body().items()==null||r.body().items().item()==null)throw new IllegalStateException("Safety guardian store API response is invalid"); List<SafetyFacilityImportItem> items=r.body().items().item().stream().filter(i->valid(i.latitude(),-90,90)&&valid(i.longitude(),-180,180)).map(i->new SafetyFacilityImportItem(SOURCE,id(i),Double.parseDouble(i.latitude()),Double.parseDouble(i.longitude()),first(i.rdnmadr(),i.lnmadr()),SOURCE)).toList(); var result=facilities.syncAll(items);created+=result.createdCount();updated+=result.updatedCount();total=r.body().totalCount();if((maxPages>0&&page>=maxPages)||page*100>=total)return new SyncResult(page,total,created,updated);}}
 private Response fetch(int page){if(key.isBlank())throw new IllegalStateException("Safety guardian store API key is not configured");URI uri=UriComponentsBuilder.fromUriString(ENDPOINT).queryParam("serviceKey",key).queryParam("pageNo",page).queryParam("numOfRows",100).queryParam("type","json").build().encode().toUri();Response r=client.get().uri(uri).retrieve().body(Response.class);if(r==null)throw new IllegalStateException("Safety guardian store API returned an empty response");return r;}
 private boolean valid(String v,double min,double max){try{double n=Double.parseDouble(v);return Double.isFinite(n)&&n>=min&&n<=max;}catch(Exception e){return false;}}
 private String id(Item i){return String.join("|",i.storNm()==null?"":i.storNm(),i.phoneNumber()==null?"":i.phoneNumber(),first(i.rdnmadr(),i.lnmadr()));} private String first(String a,String b){return a!=null&&!a.isBlank()?a:b==null?"":b;}
 public record SyncResult(int pageCount,int totalCount,int createdCount,int updatedCount){} record Response(Header header,Body body){ } record Header(String resultCode,String resultMsg){} record Body(Items items,int totalCount){} record Items(List<Item> item){} record Item(String storNm,String rdnmadr,String lnmadr,String latitude,String longitude,@JsonProperty("phoneNumber")String phoneNumber){}
}
