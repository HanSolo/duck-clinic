package dukes.startup;

import dukes.model.*;
import dukes.service.EntityManagerFactoryProducer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;

/**
 * Loads sample data once on application startup using CDI lifecycle.
 * Uses RESOURCE_LOCAL transactions directly (no EJB, no JTA).
 */
@ApplicationScoped
public class DataInitializer {

    @Inject
    private EntityManagerFactoryProducer emfp;

    public void init(@Observes @Initialized(ApplicationScoped.class) Object event) {
        EntityManager em = emfp.createEntityManager();
        try {
            // Trigger schema generation: EclipseLink creates tables on first EM use.
            // The owners table may not exist yet when we first query it, so we catch
            // that case and treat it as "empty" (proceed with seeding).
            em.getTransaction().begin();
            try {
                Long count = em.createQuery("SELECT COUNT(o) FROM Owner o", Long.class).getSingleResult();
                if (count > 0) {
                    em.getTransaction().rollback();
                    return;
                }
            } catch (Exception tableNotExists) {
                // Table not yet created — schema generation will create it below; continue seeding.
                em.getTransaction().rollback();
                em.close();
                em = emfp.createEntityManager();
                em.getTransaction().begin();
            }

            // --- Duck Types ---
            DuckType mallard  = persist(em, new DuckType("mallard"));
            DuckType muscovy  = persist(em, new DuckType("muscovy"));
            DuckType pekin    = persist(em, new DuckType("pekin"));
            DuckType runner   = persist(em, new DuckType("runner"));
            DuckType call     = persist(em, new DuckType("call"));
            DuckType teal     = persist(em, new DuckType("teal"));

            // --- Specialties ---
            Specialty radiology = persist(em, new Specialty("radiology"));
            Specialty surgery   = persist(em, new Specialty("surgery"));
            Specialty dentistry = persist(em, new Specialty("dentistry"));

            // --- Vets ---
            Vet james = new Vet(); james.setFirstName("James"); james.setLastName("Carter");
            em.persist(james);

            Vet helen = new Vet(); helen.setFirstName("Helen"); helen.setLastName("Leary");
            helen.getSpecialties().add(radiology);
            em.persist(helen);

            Vet linda = new Vet(); linda.setFirstName("Linda"); linda.setLastName("Douglas");
            linda.getSpecialties().add(surgery); linda.getSpecialties().add(dentistry);
            em.persist(linda);

            Vet rafael = new Vet(); rafael.setFirstName("Rafael"); rafael.setLastName("Ortega");
            rafael.getSpecialties().add(surgery);
            em.persist(rafael);

            Vet henry = new Vet(); henry.setFirstName("Henry"); henry.setLastName("Stevens");
            henry.getSpecialties().add(radiology);
            em.persist(henry);

            Vet sharon = new Vet(); sharon.setFirstName("Sharon"); sharon.setLastName("Jenkins");
            em.persist(sharon);

            // --- Owners & Ducks ---
            Owner george = owner("George", "Franklin", "110 W. Liberty St.", "Madison", "6085551023");
            Duck donald = duck(em, "Donald", LocalDate.of(2010, 9, 7), mallard, george);
            em.persist(george);

            Owner betty = owner("Betty", "Davis", "638 Cardinal Ave.", "Sun Prairie", "6085551749");
            Duck daisy = duck(em, "Daisy", LocalDate.of(2012, 8, 6), call, betty);
            em.persist(betty);

            Owner eduardo = owner("Eduardo", "Rodriquez", "2693 Commerce St.", "McFarland", "6085558763");
            Duck daffy = duck(em, "Daffy", LocalDate.of(2011, 4, 17), muscovy, eduardo);
            Duck dewey = duck(em, "Dewey", LocalDate.of(2010, 3, 7),  pekin,   eduardo);
            em.persist(eduardo);

            Owner harold = owner("Harold", "Davis", "563 Friendly St.", "Windsor", "6085553198");
            Duck huey = duck(em, "Huey", LocalDate.of(2010, 11, 30), runner, harold);
            em.persist(harold);

            Owner peter = owner("Peter", "McTavish", "2387 S. Fair Way", "Madison", "6085552765");
            Duck louie = duck(em, "Louie", LocalDate.of(2010, 1, 20), teal, peter);
            em.persist(peter);

            Owner jean = owner("Jean", "Coleman", "105 N. Lake St.", "Monona", "6085552654");
            Duck della   = duck(em, "Della",   LocalDate.of(2012, 9, 4), mallard, jean);
            Duck scrooge = duck(em, "Scrooge", LocalDate.of(2012, 9, 4), pekin,   jean);
            em.persist(jean);

            Owner jeff = owner("Jeff", "Black", "1450 Oak Blvd.", "Monona", "6085555387");
            Duck gladstone = duck(em, "Gladstone", LocalDate.of(2011, 8, 6), call, jeff);
            em.persist(jeff);

            Owner maria = owner("Maria", "Escobito", "345 Maple St.", "Madison", "6085557683");
            Duck gyro = duck(em, "Gyro", LocalDate.of(2007, 2, 24), runner, maria);
            em.persist(maria);

            Owner david = owner("David", "Schroeder", "2749 Blackhawk Trail", "Madison", "6085559435");
            Duck webby = duck(em, "Webby", LocalDate.of(2010, 3, 9), teal, david);
            em.persist(david);

            Owner carlos = owner("Carlos", "Estaban", "2335 Independence La.", "Waunakee", "6085555487");
            Duck fethry = duck(em, "Fethry", LocalDate.of(2012, 6, 8), muscovy, carlos);
            em.persist(carlos);


            // --- Demo data for DuckInsurance: the insured ducks are patients of the clinic too (names must match the insurer's) ---
            LocalDate today = LocalDate.now();
            DuckType cayuga   = persist(em, new DuckType("cayuga"));
            DuckType mandarin = persist(em, new DuckType("mandarin"));

            Owner anna = owner("Anna", "Keller", "Bahnhofstrasse 1", "Zurich", "4415551201");
            Duck sirQuacksalot = duck(em, "Sir Quacksalot", today.minusYears(3).minusMonths(2), mallard, anna);
            Duck duchess = duck(em, "Duchess", today.minusYears(1).minusMonths(4), cayuga, anna);
            em.persist(anna);
            Owner marcus = owner("Marcus", "Webb", "12 Canal Street", "Manchester", "4415551202");
            Duck waddles = duck(em, "Waddles", today.minusYears(6), pekin, marcus);
            em.persist(marcus);
            Owner priya = owner("Priya", "Nair", "Westersingel 8", "Rotterdam", "3115551203");
            Duck bill = duck(em, "Bill", today.minusYears(2).minusMonths(7), runner, priya);
            em.persist(priya);
            Owner tomas = owner("Tomas", "Alvarez", "Calle del Mar 5", "Valencia", "3415551204");
            Duck donQuixote = duck(em, "Don Quixote", today.minusYears(9), muscovy, tomas);
            em.persist(tomas);
            Owner lena = owner("Lena", "Fischer", "Ringstrasse 3", "Vienna", "4315551205");
            Duck ducky = duck(em, "Ducky McDuckface", today.minusMonths(7), mandarin, lena);
            em.persist(lena);
            Owner samuel = owner("Samuel", "Okafor", "Liffey Quay 9", "Dublin", "3535551206");
            Duck admiral = duck(em, "Admiral Beak", today.minusYears(4), mallard, samuel);
            Duck puddles = duck(em, "Puddles", today.minusYears(1), pekin, samuel);
            em.persist(samuel);

            em.flush();

            addVisit(em, donald,  LocalDate.of(2023, 1, 1),  "Vaccination");
            addVisit(em, donald,  LocalDate.of(2023, 3, 4),  "Annual check-up");
            addVisit(em, daisy,   LocalDate.of(2023, 1, 8),  "Annual check-up");
            addVisit(em, daffy,   LocalDate.of(2023, 2, 15), "Wing clipping");
            addVisit(em, dewey,   LocalDate.of(2023, 3, 4),  "Beak trim");
            addVisit(em, huey,    LocalDate.of(2023, 4, 12), "Foot injury");
            addVisit(em, della,   LocalDate.of(2023, 5, 1),  "Vaccination");
            addVisit(em, scrooge, LocalDate.of(2023, 5, 1),  "Annual check-up");


            // Visits that make the insurer's vet-care claims plausible or not:
            // Sir Quacksalot: hook claim 40 days ago, the clinic has the surgery (plausible)
            addVisit(em, sirQuacksalot, today.minusDays(40), "Emergency surgery: fishing hook removed, X-ray of the crop");
            addVisit(em, sirQuacksalot, today.minusDays(38), "Follow-up after surgery, stitches checked");
            // Bill: bumblefoot claim 10 days ago, the clinic has the treatment (plausible)
            addVisit(em, bill, today.minusDays(9), "Bumblefoot treatment, foot bath, bandage");
            // Don Quixote: wing fracture claim 30 days ago, but the clinic only has an old check-up (suspicious)
            addVisit(em, donQuixote, today.minusDays(420), "Annual check-up, all fine");
            // Waddles: two arthritis claims 50 and 12 days ago, the clinic has both visits (repeat claims that are plausible)
            addVisit(em, waddles, today.minusDays(50), "Arthritis flare in the left foot: X-ray, anti-inflammatory course");
            addVisit(em, waddles, today.minusDays(12), "Arthritis follow-up: joint injection, foot examination");
            // Admiral Beak and Puddles (owner Okafor): emergency surgery claims 7 and 5 days ago, the clinic has no record of either (suspicious)
            // Sir Quacksalot: a second hook surgery claim 39 days ago, the clinic has only the one surgery above (paid twice?)

            em.getTransaction().commit();
        } catch (Exception e) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            throw new RuntimeException("Failed to seed data", e);
        } finally {
            em.close();
        }
    }

    private <T> T persist(EntityManager em, T entity) {
        em.persist(entity);
        return entity;
    }

    private Owner owner(String first, String last, String address, String city, String tel) {
        Owner o = new Owner();
        o.setFirstName(first); o.setLastName(last);
        o.setAddress(address); o.setCity(city); o.setTelephone(tel);
        return o;
    }

    private Duck duck(EntityManager em, String name, LocalDate birthDate, DuckType type, Owner owner) {
        Duck d = new Duck();
        d.setName(name); d.setBirthDate(birthDate);
        d.setType(type); d.setOwner(owner);
        owner.getDucks().add(d);
        em.persist(d);
        return d;
    }

    private void addVisit(EntityManager em, Duck duck, LocalDate date, String description) {
        Visit v = new Visit();
        v.setDuck(duck); v.setVisitDate(date); v.setDescription(description);
        em.persist(v);
        duck.getVisits().add(v);
    }
}
